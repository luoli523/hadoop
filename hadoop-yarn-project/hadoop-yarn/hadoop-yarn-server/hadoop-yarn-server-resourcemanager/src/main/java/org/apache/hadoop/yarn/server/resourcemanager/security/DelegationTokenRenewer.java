/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenIdentifier;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AbstractEvent;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.security.client.RMDelegationTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppRejectedEvent;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
/**
 * Service to renew application delegation tokens.
 */
@Private
@Unstable
public class DelegationTokenRenewer extends AbstractService {
  
  private static final Log LOG = 
      LogFactory.getLog(DelegationTokenRenewer.class);
  
  public static final String SCHEME = "hdfs";

  // global single timer (daemon)
  private Timer renewalTimer;
  private RMContext rmContext;
  
  // delegation token canceler thread
  private DelegationTokenCancelThread dtCancelThread =
    new DelegationTokenCancelThread();
  private ThreadPoolExecutor renewerService;

  private ConcurrentMap<ApplicationId, Set<DelegationTokenToRenew>> appTokens =
      new ConcurrentHashMap<ApplicationId, Set<DelegationTokenToRenew>>();

  private ConcurrentMap<Token<?>, DelegationTokenToRenew> allTokens =
      new ConcurrentHashMap<Token<?>, DelegationTokenToRenew>();

  private final ConcurrentMap<ApplicationId, Long> delayedRemovalMap =
      new ConcurrentHashMap<ApplicationId, Long>();

  private long tokenRemovalDelayMs;
  
  private Thread delayedRemovalThread;
  private ReadWriteLock serviceStateLock = new ReentrantReadWriteLock();
  private volatile boolean isServiceStarted;
  private LinkedBlockingQueue<DelegationTokenRenewerEvent> pendingEventQueue;
  
  private boolean tokenKeepAliveEnabled;
  private boolean hasProxyUserPrivileges;
  private long credentialsValidTimeRemaining;

  // this config is supposedly not used by end-users.
  public static final String RM_SYSTEM_CREDENTIALS_VALID_TIME_REMAINING =
      YarnConfiguration.RM_PREFIX + "system-credentials.valid-time-remaining";
  public static final long DEFAULT_RM_SYSTEM_CREDENTIALS_VALID_TIME_REMAINING =
      10800000; // 3h

  public DelegationTokenRenewer() {
    super(DelegationTokenRenewer.class.getName());
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    this.hasProxyUserPrivileges =
        conf.getBoolean(YarnConfiguration.RM_PROXY_USER_PRIVILEGES_ENABLED,
          YarnConfiguration.DEFAULT_RM_PROXY_USER_PRIVILEGES_ENABLED);
    this.tokenKeepAliveEnabled =
        conf.getBoolean(YarnConfiguration.LOG_AGGREGATION_ENABLED,
            YarnConfiguration.DEFAULT_LOG_AGGREGATION_ENABLED);
    this.tokenRemovalDelayMs =
        conf.getInt(YarnConfiguration.RM_NM_EXPIRY_INTERVAL_MS,
            YarnConfiguration.DEFAULT_RM_NM_EXPIRY_INTERVAL_MS);
    this.credentialsValidTimeRemaining =
        conf.getLong(RM_SYSTEM_CREDENTIALS_VALID_TIME_REMAINING,
          DEFAULT_RM_SYSTEM_CREDENTIALS_VALID_TIME_REMAINING);
    setLocalSecretManagerAndServiceAddr();
    renewerService = createNewThreadPoolService(conf);
    pendingEventQueue = new LinkedBlockingQueue<DelegationTokenRenewerEvent>();
    renewalTimer = new Timer(true);
    super.serviceInit(conf);
  }

  protected ThreadPoolExecutor createNewThreadPoolService(Configuration conf) {
    int nThreads = conf.getInt(
        YarnConfiguration.RM_DELEGATION_TOKEN_RENEWER_THREAD_COUNT,
        YarnConfiguration.DEFAULT_RM_DELEGATION_TOKEN_RENEWER_THREAD_COUNT);

    ThreadFactory tf = new ThreadFactoryBuilder()
        .setNameFormat("DelegationTokenRenewer #%d")
        .build();
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(nThreads, nThreads, 3L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    pool.setThreadFactory(tf);
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  // enable RM to short-circuit token operations directly to itself
  private void setLocalSecretManagerAndServiceAddr() {
    RMDelegationTokenIdentifier.Renewer.setSecretManager(rmContext
      .getRMDelegationTokenSecretManager(), rmContext.getClientRMService()
      .getBindAddress());
  }

  @Override
  protected void serviceStart() throws Exception {
    dtCancelThread.start();
    if (tokenKeepAliveEnabled) {
      delayedRemovalThread =
          new Thread(new DelayedTokenRemovalRunnable(getConfig()),
              "DelayedTokenCanceller");
      delayedRemovalThread.start();
    }

    setLocalSecretManagerAndServiceAddr();
    serviceStateLock.writeLock().lock();
    isServiceStarted = true;
    serviceStateLock.writeLock().unlock();
    while(!pendingEventQueue.isEmpty()) {
      processDelegationTokenRenewerEvent(pendingEventQueue.take());
    }
    super.serviceStart();
  }

  private void processDelegationTokenRenewerEvent(
      DelegationTokenRenewerEvent evt) {
    serviceStateLock.readLock().lock();
    try {
      if (isServiceStarted) {
        renewerService.execute(new DelegationTokenRenewerRunnable(evt));
      } else {
        pendingEventQueue.add(evt);
      }
    } finally {
      serviceStateLock.readLock().unlock();
    }
  }

  @Override
  protected void serviceStop() {
    if (renewalTimer != null) {
      renewalTimer.cancel();
    }
    appTokens.clear();
    allTokens.clear();
    this.renewerService.shutdown();
    dtCancelThread.interrupt();
    try {
      dtCancelThread.join(1000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (tokenKeepAliveEnabled && delayedRemovalThread != null) {
      delayedRemovalThread.interrupt();
      try {
        delayedRemovalThread.join(1000);
      } catch (InterruptedException e) {
        LOG.info("Interrupted while joining on delayed removal thread.", e);
      }
    }
  }

  /**
   * class that is used for keeping tracks of DT to renew
   *
   */
  @VisibleForTesting
  protected static class DelegationTokenToRenew {
    public final Token<?> token;
    public final ApplicationId applicationId;
    public final Configuration conf;
    public long expirationDate;
    public TimerTask timerTask;
    public volatile boolean shouldCancelAtEnd;
    public long maxDate;
    public String user;

    public DelegationTokenToRenew(ApplicationId jId, Token<?> token,
        Configuration conf, long expirationDate, boolean shouldCancelAtEnd,
        String user) {
      this.token = token;
      this.user = user;
      if (token.getKind().equals(new Text("HDFS_DELEGATION_TOKEN"))) {
        try {
          AbstractDelegationTokenIdentifier identifier =
              (AbstractDelegationTokenIdentifier) token.decodeIdentifier();
          maxDate = identifier.getMaxDate();
        } catch (IOException e) {
          throw new YarnRuntimeException(e);
        }
      }
      this.applicationId = jId;
      this.conf = conf;
      this.expirationDate = expirationDate;
      this.timerTask = null;
      this.shouldCancelAtEnd = shouldCancelAtEnd;
    }
    
    public void setTimerTask(TimerTask tTask) {
      timerTask = tTask;
    }
    
    @Override
    public String toString() {
      return token + ";exp=" + expirationDate;
    }
    
    @Override
    public boolean equals(Object obj) {
      return obj instanceof DelegationTokenToRenew &&
        token.equals(((DelegationTokenToRenew)obj).token);
    }
    
    @Override
    public int hashCode() {
      return token.hashCode();
    }
  }
  
  
  private static class DelegationTokenCancelThread extends Thread {
    private static class TokenWithConf {
      Token<?> token;
      Configuration conf;
      TokenWithConf(Token<?> token, Configuration conf) {
        this.token = token;
        this.conf = conf;
      }
    }
    private LinkedBlockingQueue<TokenWithConf> queue =  
      new LinkedBlockingQueue<TokenWithConf>();
     
    public DelegationTokenCancelThread() {
      super("Delegation Token Canceler");
      setDaemon(true);
    }
    public void cancelToken(Token<?> token,  
        Configuration conf) {
      TokenWithConf tokenWithConf = new TokenWithConf(token, conf);
      while (!queue.offer(tokenWithConf)) {
        LOG.warn("Unable to add token " + token + " for cancellation. " +
        		 "Will retry..");
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }

    public void run() {
      TokenWithConf tokenWithConf = null;
      while (true) {
        try {
          tokenWithConf = queue.take();
          final TokenWithConf current = tokenWithConf;
          if (LOG.isDebugEnabled()) {
            LOG.debug("Cancelling token " + tokenWithConf.token.getService());
          }
          // need to use doAs so that http can find the kerberos tgt
          UserGroupInformation.getLoginUser()
            .doAs(new PrivilegedExceptionAction<Void>(){

              @Override
              public Void run() throws Exception {
                current.token.cancel(current.conf);
                return null;
              }
            });
        } catch (IOException e) {
          LOG.warn("Failed to cancel token " + tokenWithConf.token + " " +  
              StringUtils.stringifyException(e));
        } catch (RuntimeException e) {
          LOG.warn("Failed to cancel token " + tokenWithConf.token + " " +  
              StringUtils.stringifyException(e));
        } catch (InterruptedException ie) {
          return;
        } catch (Throwable t) {
          LOG.warn("Got exception " + StringUtils.stringifyException(t) + 
                   ". Exiting..");
          System.exit(-1);
        }
      }
    }
  }

  @VisibleForTesting
  public Set<Token<?>> getDelegationTokens() {
    Set<Token<?>> tokens = new HashSet<Token<?>>();
    for (Set<DelegationTokenToRenew> tokenList : appTokens.values()) {
      for (DelegationTokenToRenew token : tokenList) {
        tokens.add(token.token);
      }
    }
    return tokens;
  }

  /**
   * Asynchronously add application tokens for renewal.
   * @param applicationId added application
   * @param ts tokens
   * @param shouldCancelAtEnd true if tokens should be canceled when the app is
   * done else false. 
   * @param user user
   */
  public void addApplicationAsync(ApplicationId applicationId, Credentials ts,
      boolean shouldCancelAtEnd, String user) {
    processDelegationTokenRenewerEvent(new DelegationTokenRenewerAppSubmitEvent(
      applicationId, ts, shouldCancelAtEnd, user));
  }

  /**
   * Synchronously renew delegation tokens.
   * @param user user
   */
  public void addApplicationSync(ApplicationId applicationId, Credentials ts,
      boolean shouldCancelAtEnd, String user) throws IOException,
      InterruptedException {
    handleAppSubmitEvent(new DelegationTokenRenewerAppSubmitEvent(
      applicationId, ts, shouldCancelAtEnd, user));
  }

  private void handleAppSubmitEvent(DelegationTokenRenewerAppSubmitEvent evt)
      throws IOException, InterruptedException {
    ApplicationId applicationId = evt.getApplicationId();
    Credentials ts = evt.getCredentials();
    boolean shouldCancelAtEnd = evt.shouldCancelAtEnd();
    if (ts == null) {
      return; // nothing to add
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Registering tokens for renewal for:" +
          " appId = " + applicationId);
    }

    Collection<Token<?>> tokens = ts.getAllTokens();
    long now = System.currentTimeMillis();

    // find tokens for renewal, but don't add timers until we know
    // all renewable tokens are valid
    // At RM restart it is safe to assume that all the previously added tokens
    // are valid
    appTokens.put(applicationId,
      Collections.synchronizedSet(new HashSet<DelegationTokenToRenew>()));
    Set<DelegationTokenToRenew> tokenList = new HashSet<DelegationTokenToRenew>();
    boolean hasHdfsToken = false;
    for (Token<?> token : tokens) {
      if (token.isManaged()) {
        if (token.getKind().equals(new Text("HDFS_DELEGATION_TOKEN"))) {
          LOG.info(applicationId + " found existing hdfs token " + token);
          hasHdfsToken = true;
        }

        DelegationTokenToRenew dttr = allTokens.get(token);
        if (dttr != null) {
          // If any of the jobs sharing the same token doesn't want to cancel
          // the token, we should not cancel the token.
          if (!evt.shouldCancelAtEnd) {
            dttr.shouldCancelAtEnd = evt.shouldCancelAtEnd;
            LOG.info("Set shouldCancelAtEnd=" + shouldCancelAtEnd
                + " for token " + dttr.token);
          }
          continue;
        }

        tokenList.add(new DelegationTokenToRenew(applicationId, token,
          getConfig(), now, shouldCancelAtEnd, evt.getUser()));
      }
    }

    if (!tokenList.isEmpty()) {
      // Renewing token and adding it to timer calls are separated purposefully
      // If user provides incorrect token then it should not be added for
      // renewal.
      for (DelegationTokenToRenew dtr : tokenList) {
        try {
          renewToken(dtr);
        } catch (IOException ioe) {
          throw new IOException("Failed to renew token: " + dtr.token, ioe);
        }
      }
      for (DelegationTokenToRenew dtr : tokenList) {
        appTokens.get(applicationId).add(dtr);
        allTokens.put(dtr.token, dtr);
        setTimerForTokenRenewal(dtr);
      }
    }

    if (!hasHdfsToken) {
      requestNewHdfsDelegationToken(applicationId, evt.getUser(),
        shouldCancelAtEnd);
    }
  }

  /**
   * Task - to renew a token
   *
   */
  private class RenewalTimerTask extends TimerTask {
    private DelegationTokenToRenew dttr;
    private AtomicBoolean cancelled = new AtomicBoolean(false);
    
    RenewalTimerTask(DelegationTokenToRenew t) {  
      dttr = t;  
    }
    
    @Override
    public void run() {
      if (cancelled.get()) {
        return;
      }

      Token<?> token = dttr.token;

      try {
        requestNewHdfsDelegationTokenIfNeeded(dttr);
        // if the token is not replaced by a new token, renew the token
        if (appTokens.get(dttr.applicationId).contains(dttr)) {
          renewToken(dttr);
          setTimerForTokenRenewal(dttr);// set the next one
        } else {
          LOG.info("The token was removed already. Token = [" +dttr +"]");
        }
      } catch (Exception e) {
        LOG.error("Exception renewing token" + token + ". Not rescheduled", e);
        removeFailedDelegationToken(dttr);
      }
    }

    @Override
    public boolean cancel() {
      cancelled.set(true);
      return super.cancel();
    }
  }
  
  /**
   * set task to renew the token
   */
  @VisibleForTesting
  protected void setTimerForTokenRenewal(DelegationTokenToRenew token)
      throws IOException {
      
    // calculate timer time
    long expiresIn = token.expirationDate - System.currentTimeMillis();
    long renewIn = token.expirationDate - expiresIn/10; // little bit before the expiration
    // need to create new task every time
    TimerTask tTask = new RenewalTimerTask(token);
    token.setTimerTask(tTask); // keep reference to the timer

    renewalTimer.schedule(token.timerTask, new Date(renewIn));
    LOG.info("Renew " + token + " in " + expiresIn + " ms, appId = "
        + token.applicationId);
  }

  // renew a token
  @VisibleForTesting
  protected void renewToken(final DelegationTokenToRenew dttr)
      throws IOException {
    // need to use doAs so that http can find the kerberos tgt
    // NOTE: token renewers should be responsible for the correct UGI!
    try {
      dttr.expirationDate =
          UserGroupInformation.getLoginUser().doAs(
            new PrivilegedExceptionAction<Long>() {
              @Override
              public Long run() throws Exception {
                return dttr.token.renew(dttr.conf);
              }
            });
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
    LOG.info("Renewed delegation-token= [" + dttr + "], for "
        + dttr.applicationId);
  }

  // Request new hdfs token if the token is about to expire, and remove the old
  // token from the tokenToRenew list
  private void requestNewHdfsDelegationTokenIfNeeded(
      final DelegationTokenToRenew dttr) throws IOException,
      InterruptedException {

    if (hasProxyUserPrivileges
        && dttr.maxDate - dttr.expirationDate < credentialsValidTimeRemaining
        && dttr.token.getKind().equals(new Text("HDFS_DELEGATION_TOKEN"))) {

      // remove all old expiring hdfs tokens for this application.
      Set<DelegationTokenToRenew> tokenSet = appTokens.get(dttr.applicationId);
      if (tokenSet != null && !tokenSet.isEmpty()) {
        Iterator<DelegationTokenToRenew> iter = tokenSet.iterator();
        synchronized (tokenSet) {
          while (iter.hasNext()) {
            DelegationTokenToRenew t = iter.next();
            if (t.token.getKind().equals(new Text("HDFS_DELEGATION_TOKEN"))) {
              iter.remove();
              if (t.timerTask != null) {
                t.timerTask.cancel();
              }
              LOG.info("Removed expiring token " + t);
            }
          }
        }
      }
      LOG.info("Token= (" + dttr + ") is expiring, request new token.");
      requestNewHdfsDelegationToken(dttr.applicationId, dttr.user,
        dttr.shouldCancelAtEnd);
    }
  }

  private void requestNewHdfsDelegationToken(ApplicationId applicationId,
      String user, boolean shouldCancelAtEnd) throws IOException,
      InterruptedException {
    if (!hasProxyUserPrivileges) {
      LOG.info("RM proxy-user privilege is not enabled. Skip requesting hdfs tokens.");
      return;
    }
    // Get new hdfs tokens for this user
    Credentials credentials = new Credentials();
    Token<?>[] newTokens = obtainSystemTokensForUser(user, credentials);

    // Add new tokens to the toRenew list.
    LOG.info("Received new tokens for " + applicationId + ". Received "
        + newTokens.length + " tokens.");
    if (newTokens.length > 0) {
      for (Token<?> token : newTokens) {
        if (token.isManaged()) {
          DelegationTokenToRenew tokenToRenew =
              new DelegationTokenToRenew(applicationId, token, getConfig(),
                Time.now(), shouldCancelAtEnd, user);
          // renew the token to get the next expiration date.
          renewToken(tokenToRenew);
          setTimerForTokenRenewal(tokenToRenew);
          appTokens.get(applicationId).add(tokenToRenew);
          LOG.info("Received new token " + token);
        }
      }
    }
    DataOutputBuffer dob = new DataOutputBuffer();
    credentials.writeTokenStorageToStream(dob);
    ByteBuffer byteBuffer = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
    rmContext.getSystemCredentialsForApps().put(applicationId, byteBuffer);
  }

  protected Token<?>[] obtainSystemTokensForUser(String user,
      final Credentials credentials) throws IOException, InterruptedException {
    // Get new hdfs tokens on behalf of this user
    UserGroupInformation proxyUser =
        UserGroupInformation.createProxyUser(user,
          UserGroupInformation.getLoginUser());
    Token<?>[] newTokens =
        proxyUser.doAs(new PrivilegedExceptionAction<Token<?>[]>() {
          @Override
          public Token<?>[] run() throws Exception {
            return FileSystem.get(getConfig()).addDelegationTokens(
              UserGroupInformation.getLoginUser().getUserName(), credentials);
          }
        });
    return newTokens;
  }

  // cancel a token
  private void cancelToken(DelegationTokenToRenew t) {
    if(t.shouldCancelAtEnd) {
      dtCancelThread.cancelToken(t.token, t.conf);
    } else {
      LOG.info("Did not cancel "+t);
    }
  }
  
  /**
   * removing failed DT
   */
  private void removeFailedDelegationToken(DelegationTokenToRenew t) {
    ApplicationId applicationId = t.applicationId;
    LOG.error("removing failed delegation token for appid=" + applicationId
        + ";t=" + t.token.getService());
    appTokens.get(applicationId).remove(t);
    allTokens.remove(t.token);

    // cancel the timer
    if (t.timerTask != null) {
      t.timerTask.cancel();
    }
  }

  /**
   * Removing delegation token for completed applications.
   * @param applicationId completed application
   */
  public void applicationFinished(ApplicationId applicationId) {
    processDelegationTokenRenewerEvent(new DelegationTokenRenewerEvent(
        applicationId,
        DelegationTokenRenewerEventType.FINISH_APPLICATION));
  }

  private void handleAppFinishEvent(DelegationTokenRenewerEvent evt) {
    if (!tokenKeepAliveEnabled) {
      removeApplicationFromRenewal(evt.getApplicationId());
    } else {
      delayedRemovalMap.put(evt.getApplicationId(), System.currentTimeMillis()
          + tokenRemovalDelayMs);
    }
  }
  
  /**
   * Add a list of applications to the keep alive list. If an appId already
   * exists, update it's keep-alive time.
   * 
   * @param appIds
   *          the list of applicationIds to be kept alive.
   * 
   */
  public void updateKeepAliveApplications(List<ApplicationId> appIds) {
    if (tokenKeepAliveEnabled && appIds != null && appIds.size() > 0) {
      for (ApplicationId appId : appIds) {
        delayedRemovalMap.put(appId, System.currentTimeMillis()
            + tokenRemovalDelayMs);
      }
    }
  }

  private void removeApplicationFromRenewal(ApplicationId applicationId) {
    rmContext.getSystemCredentialsForApps().remove(applicationId);
    Set<DelegationTokenToRenew> tokens = appTokens.get(applicationId);

    if (tokens != null && !tokens.isEmpty()) {
      synchronized (tokens) {
        Iterator<DelegationTokenToRenew> it = tokens.iterator();
        while (it.hasNext()) {
          DelegationTokenToRenew dttr = it.next();
          if (LOG.isDebugEnabled()) {
            LOG.debug("Removing delegation token for appId=" + applicationId
                + "; token=" + dttr.token.getService());
          }

          // cancel the timer
          if (dttr.timerTask != null)
            dttr.timerTask.cancel();

          // cancel the token
          cancelToken(dttr);

          it.remove();
          allTokens.remove(dttr.token);
        }
      }
    }

    if(tokens != null && tokens.isEmpty()) {
      appTokens.remove(applicationId);
    }
  }

  /**
   * Takes care of cancelling app delegation tokens after the configured
   * cancellation delay, taking into consideration keep-alive requests.
   * 
   */
  private class DelayedTokenRemovalRunnable implements Runnable {

    private long waitTimeMs;

    DelayedTokenRemovalRunnable(Configuration conf) {
      waitTimeMs =
          conf.getLong(
              YarnConfiguration.RM_DELAYED_DELEGATION_TOKEN_REMOVAL_INTERVAL_MS,
              YarnConfiguration.DEFAULT_RM_DELAYED_DELEGATION_TOKEN_REMOVAL_INTERVAL_MS);
    }

    @Override
    public void run() {
      List<ApplicationId> toCancel = new ArrayList<ApplicationId>();
      while (!Thread.currentThread().isInterrupted()) {
        Iterator<Entry<ApplicationId, Long>> it =
            delayedRemovalMap.entrySet().iterator();
        toCancel.clear();
        while (it.hasNext()) {
          Entry<ApplicationId, Long> e = it.next();
          if (e.getValue() < System.currentTimeMillis()) {
            toCancel.add(e.getKey());
          }
        }
        for (ApplicationId appId : toCancel) {
          removeApplicationFromRenewal(appId);
          delayedRemovalMap.remove(appId);
        }
        synchronized (this) {
          try {
            wait(waitTimeMs);
          } catch (InterruptedException e) {
            LOG.info("Delayed Deletion Thread Interrupted. Shutting it down");
            return;
          }
        }
      }
    }
  }
  
  public void setRMContext(RMContext rmContext) {
    this.rmContext = rmContext;
  }
  
  /*
   * This will run as a separate thread and will process individual events. It
   * is done in this way to make sure that the token renewal as a part of
   * application submission and token removal as a part of application finish
   * is asynchronous in nature.
   */
  private final class DelegationTokenRenewerRunnable
      implements Runnable {

    private DelegationTokenRenewerEvent evt;
    
    public DelegationTokenRenewerRunnable(DelegationTokenRenewerEvent evt) {
      this.evt = evt;
    }
    
    @Override
    public void run() {
      if (evt instanceof DelegationTokenRenewerAppSubmitEvent) {
        DelegationTokenRenewerAppSubmitEvent appSubmitEvt =
            (DelegationTokenRenewerAppSubmitEvent) evt;
        handleDTRenewerAppSubmitEvent(appSubmitEvt);
      } else if (evt.getType().equals(
          DelegationTokenRenewerEventType.FINISH_APPLICATION)) {
        DelegationTokenRenewer.this.handleAppFinishEvent(evt);
      }
    }

    @SuppressWarnings("unchecked")
    private void handleDTRenewerAppSubmitEvent(
        DelegationTokenRenewerAppSubmitEvent event) {
      /*
       * For applications submitted with delegation tokens we are not submitting
       * the application to scheduler from RMAppManager. Instead we are doing
       * it from here. The primary goal is to make token renewal as a part of
       * application submission asynchronous so that client thread is not
       * blocked during app submission.
       */
      try {
        // Setup tokens for renewal
        DelegationTokenRenewer.this.handleAppSubmitEvent(event);
        rmContext.getDispatcher().getEventHandler()
            .handle(new RMAppEvent(event.getApplicationId(), RMAppEventType.START));
      } catch (Throwable t) {
        LOG.warn(
            "Unable to add the application to the delegation token renewer.",
            t);
        // Sending APP_REJECTED is fine, since we assume that the
        // RMApp is in NEW state and thus we havne't yet informed the
        // Scheduler about the existence of the application
        rmContext.getDispatcher().getEventHandler().handle(
            new RMAppRejectedEvent(event.getApplicationId(), t.getMessage()));
      }
    }
  }
  
  static class DelegationTokenRenewerAppSubmitEvent extends
      DelegationTokenRenewerEvent {

    private Credentials credentials;
    private boolean shouldCancelAtEnd;
    private String user;

    public DelegationTokenRenewerAppSubmitEvent(ApplicationId appId,
        Credentials credentails, boolean shouldCancelAtEnd, String user) {
      super(appId, DelegationTokenRenewerEventType.VERIFY_AND_START_APPLICATION);
      this.credentials = credentails;
      this.shouldCancelAtEnd = shouldCancelAtEnd;
      this.user = user;
    }

    public Credentials getCredentials() {
      return credentials;
    }

    public boolean shouldCancelAtEnd() {
      return shouldCancelAtEnd;
    }

    public String getUser() {
      return user;
    }
  }
  
  enum DelegationTokenRenewerEventType {
    VERIFY_AND_START_APPLICATION,
    FINISH_APPLICATION
  }
  
  private static class DelegationTokenRenewerEvent extends
      AbstractEvent<DelegationTokenRenewerEventType> {

    private ApplicationId appId;

    public DelegationTokenRenewerEvent(ApplicationId appId,
        DelegationTokenRenewerEventType type) {
      super(type);
      this.appId = appId;
    }

    public ApplicationId getApplicationId() {
      return appId;
    }
  }

  // only for testing
  protected ConcurrentMap<Token<?>, DelegationTokenToRenew> getAllTokens() {
    return allTokens;
  }
}
