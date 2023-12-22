package ch.ejpd.lgs.searchindex.client.service.sync;

import static ch.ejpd.lgs.searchindex.client.service.sync.FullSyncSeedState.*;
import static ch.ejpd.lgs.searchindex.client.service.sync.FullSyncSettings.*;

import ch.ejpd.lgs.searchindex.client.repository.LandRegisterRepository;
import ch.ejpd.lgs.searchindex.client.repository.SettingRepository;
import ch.ejpd.lgs.searchindex.client.service.amqp.QueueStatsService;
import ch.ejpd.lgs.searchindex.client.service.amqp.Queues;
import ch.ejpd.lgs.searchindex.client.service.exception.StateChangeConflictingException;
import ch.ejpd.lgs.searchindex.client.service.exception.StateChangeSenderIdConflictingException;
import ch.ejpd.lgs.searchindex.client.util.SenderUtil;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FullSyncStateManager {
  private static final boolean BLOCKING_PURGE = false;

  private final AtomicReference<FullSyncSeedState> fullSyncSeedState = new AtomicReference<>(READY);
  private final AtomicReference<String> currentFullSyncSenderId = new AtomicReference<>(null);
  private final AtomicReference<UUID> currentFullSyncJobId = new AtomicReference<>(null);
  private final AtomicInteger currentFullSyncPage = new AtomicInteger(0);
  private final AtomicInteger fullSyncMessagesTotal = new AtomicInteger(0);
  private final AtomicInteger fullSyncMessagesProcessed = new AtomicInteger(0);
  private final AtomicInteger currentFullSyncMessageCounter = new AtomicInteger(0);
  private final ConcurrentMap<String, Integer> fullSyncMessagesTotalByLandRegister =
      new ConcurrentHashMap<>();

  private final FullSyncSettingsStore settingsStore;
  private final QueueStatsService queueStatsService;
  private final RabbitAdmin rabbitAdmin;
  @Getter private final SenderUtil senderUtil;

  @Autowired
  public FullSyncStateManager(
      SettingRepository settingRepository,
      LandRegisterRepository landRegisterRepository,
      QueueStatsService queueStatsService,
      RabbitAdmin rabbitAdmin,
      SenderUtil senderUtil) {
    this.settingsStore = new FullSyncSettingsStore(settingRepository, landRegisterRepository);
    this.queueStatsService = queueStatsService;
    this.rabbitAdmin = rabbitAdmin;
    this.senderUtil = senderUtil;
    loadPersistedSettingsOrSystemDefaults();
  }

  protected void loadPersistedSettingsOrSystemDefaults() {
    try {
      fullSyncSeedState.set(
          FullSyncSeedState.valueOf(settingsStore.loadPersistedSetting(FULL_SYNC_STORED_STATE)));
      final String jobId = settingsStore.loadPersistedSetting(FULL_SYNC_STORED_JOB_ID);
      if (jobId != null) {
        currentFullSyncJobId.set(UUID.fromString(jobId));
      }
      currentFullSyncSenderId.set(settingsStore.loadPersistedSetting(FULL_SYNC_STORED_SENDER_ID));
      currentFullSyncPage.set(
          Integer.parseInt(settingsStore.loadPersistedSetting(FULL_SYNC_STORED_PAGE)));
      fullSyncMessagesTotal.set(
          Integer.parseInt(settingsStore.loadPersistedSetting(FULL_SYNC_STORED_MESSAGE_TOTAL)));
      fullSyncMessagesProcessed.set(
          Integer.parseInt(settingsStore.loadPersistedSetting(FULL_SYNC_STORED_MESSAGE_PROCESSED)));
      if (!senderUtil.isInMultiSenderMode()) {
        settingsStore.loadPersistedLandRegisterSetting(senderUtil.getSingleSenderId());
      }
    } catch (Exception e) {
      log.error("Failed to load defaults from db; reason: {}.", e.getMessage());
    }
  }

  public boolean isInStateSeeding() {
    return getFullSyncJobState() == FullSyncSeedState.SEEDING;
  }

  public boolean isInStateSeeded() {
    return getFullSyncJobState() == FullSyncSeedState.SEEDED;
  }

  public boolean isInStateSending() {
    return getFullSyncJobState() == FullSyncSeedState.SENDING;
  }

  public boolean isInStateFailed() {
    return getFullSyncJobState() == FullSyncSeedState.FAILED;
  }

  public boolean isIncomingQueueEmpty() {
    return queueStatsService.getQueueCount(Queues.PERSONDATA_FULL_INCOMING) == 0;
  }

  public boolean isOutgoingQueueEmpty() {
    return queueStatsService.getQueueCount(Queues.PERSONDATA_FULL_OUTGOING) == 0;
  }

  public boolean isFailedQueueEmpty() {
    return queueStatsService.getQueueCount(Queues.PERSONDATA_FULL_FAILED) == 0;
  }

  private void setFullSyncJobState(FullSyncSeedState state) {
    log.info(
        "Changed job state [{} -> {}] of full sync job [jobId: {}, senderId: {}]",
        fullSyncSeedState,
        state,
        currentFullSyncJobId,
        currentFullSyncSenderId);
    fullSyncSeedState.set(state);
    settingsStore.persistSetting(FULL_SYNC_STORED_STATE, state.toString());
    if (!senderUtil.isInMultiSenderMode()) {
      settingsStore.persistLandRegisterSetting(
          new HashMap<>(fullSyncMessagesTotalByLandRegister), currentFullSyncSenderId.get());
    }
  }

  public UUID getCurrentFullSyncJobId() {
    return currentFullSyncJobId.get();
  }

  private void setCurrentFullSyncJobId(UUID syncJobId) {
    currentFullSyncJobId.set(syncJobId);
    settingsStore.persistSetting(
        FULL_SYNC_STORED_JOB_ID, Optional.ofNullable(syncJobId).map(UUID::toString).orElse(null));
  }

  public String getCurrentFullSyncSenderId() {
    return currentFullSyncSenderId.get();
  }

  private void setCurrentFullSyncSenderId(String senderId) {
    currentFullSyncSenderId.set(senderId);
    settingsStore.persistSetting(FULL_SYNC_STORED_SENDER_ID, senderId);
  }

  private void clearLandRegisterSetting(String senderId) {
    settingsStore.clearLandRegisterSetting(senderId);
  }

  public FullSyncSeedState getFullSyncJobState() {
    return fullSyncSeedState.get();
  }

  private void resetCurrentFullSyncPage() {
    currentFullSyncPage.set(-1);
    settingsStore.persistSetting(FULL_SYNC_STORED_PAGE, "-1");
  }

  public Integer getCurrentFullSyncPage() {
    return currentFullSyncPage.get();
  }

  private void setFullSyncMessagesTotal(@NonNull Integer value) {
    fullSyncMessagesTotal.set(value);
    settingsStore.persistSetting(FULL_SYNC_STORED_MESSAGE_TOTAL, value.toString());
  }

  public Integer getFullSyncMessagesTotal() {
    return fullSyncMessagesTotal.get();
  }

  private void resetFullSyncMessagesProcessed() {
    fullSyncMessagesProcessed.set(0);
    settingsStore.persistSetting(FULL_SYNC_STORED_MESSAGE_PROCESSED, "0");
  }

  public Integer getFullSyncMessagesProcessed() {
    return fullSyncMessagesProcessed.get();
  }

  public void incNumMessagesProcessed(int numProcessed) {
    fullSyncMessagesProcessed.getAndAdd(numProcessed);
    settingsStore.persistSetting(
        FULL_SYNC_STORED_MESSAGE_PROCESSED, String.valueOf(fullSyncMessagesProcessed.get()));
  }

  public void incFullSeedMessageCounter() {
    currentFullSyncMessageCounter.getAndIncrement();
  }

  public void incLandRegisterMessageCounter(String landRegister) {
    if (Strings.isEmpty(landRegister)) {
      return;
    }

    fullSyncMessagesTotalByLandRegister.putIfAbsent(landRegister, 0);
    fullSyncMessagesTotalByLandRegister.compute(landRegister, (k, v) -> v + 1);
  }

  public void decrLandRegisterMessageCounter(Map<String, Integer> landRegisters) {
    for (String landRegister : landRegisters.keySet()) {
      fullSyncMessagesTotalByLandRegister.compute(
          landRegister, (k, v) -> v - landRegisters.get(landRegister));
    }

    settingsStore.persistLandRegisterSetting(
        fullSyncMessagesTotalByLandRegister, currentFullSyncSenderId.get());
  }

  public Map<String, Integer> getLandRegisters() {
    return new HashMap<>(fullSyncMessagesTotalByLandRegister);
  }

  public void startFullSync(final String inputSenderId) {
    String senderId = senderUtil.getSenderId(inputSenderId);
    if (Arrays.asList(COMPLETED, READY).contains(getFullSyncJobState())) {
      if (getFullSyncJobState() != READY) {
        resetFullSync(false, senderId);
      }
      setCurrentFullSyncSenderId(senderId);
      setCurrentFullSyncJobId(UUID.randomUUID());
      setFullSyncJobState(SEEDING);
      return;
    }
    throw new StateChangeConflictingException(getFullSyncJobState(), SEEDING);
  }

  public void submitFullSync(final String senderId) {
    if (getFullSyncJobState() == SEEDING) {
      if (!getCurrentFullSyncSenderId().equals(senderUtil.getSenderId(senderId))) {
        throw new StateChangeSenderIdConflictingException(
            String.format(
                "Mismatching senderIds for force stateChange - currentSenderId: %s, commands senderId: %s.",
                getCurrentFullSyncSenderId(), senderId));
      }

      setFullSyncJobState(SEEDED);
      setFullSyncMessagesTotal(currentFullSyncMessageCounter.get());
      if (!senderUtil.isInMultiSenderMode()) {
        settingsStore.persistLandRegisterSetting(
            new HashMap<>(fullSyncMessagesTotalByLandRegister), currentFullSyncSenderId.get());
      }
      currentFullSyncMessageCounter.set(0);
      return;
    }
    throw new StateChangeConflictingException(getFullSyncJobState(), SEEDED);
  }

  public void startSendingFullSync() {
    if (getFullSyncJobState() == SEEDED) {
      setFullSyncJobState(SENDING);
      return;
    }
    throw new StateChangeConflictingException(getFullSyncJobState(), SENDING);
  }

  public void resetFullSync(boolean force, final String senderId) {
    // disable force mode from different senderId
    if (senderId != null
        && getCurrentFullSyncSenderId() != null
        && !senderId.equals(getCurrentFullSyncSenderId())
        && force) {
      throw new StateChangeSenderIdConflictingException(
          String.format(
              "Mismatching senderIds for force stateChange - currentSenderId: %s, commands senderId: %s.",
              getCurrentFullSyncSenderId(), senderId));
    }
    if (force || Arrays.asList(COMPLETED, FAILED).contains(getFullSyncJobState())) {
      rabbitAdmin.purgeQueue(Queues.PERSONDATA_FULL_INCOMING, BLOCKING_PURGE);
      rabbitAdmin.purgeQueue(Queues.PERSONDATA_FULL_OUTGOING, BLOCKING_PURGE);
      rabbitAdmin.purgeQueue(Queues.PERSONDATA_FULL_FAILED, BLOCKING_PURGE);

      setCurrentFullSyncJobId(null);
      setCurrentFullSyncSenderId(null);
      setFullSyncJobState(READY);
      clearLandRegisterSetting(senderId);
      setFullSyncMessagesTotal(0);
      resetCurrentFullSyncPage();
      resetFullSyncMessagesProcessed();
      return;
    }
    throw new StateChangeConflictingException(getFullSyncJobState(), READY);
  }

  public void completedFullSync() {
    if (getFullSyncJobState() == SENDING) {
      setFullSyncJobState(COMPLETED);
      return;
    }
    throw new StateChangeConflictingException(getFullSyncJobState(), COMPLETED);
  }

  public void failFullSync() {
    if (Arrays.asList(SEEDING, SENDING, COMPLETED).contains(getFullSyncJobState())) {
      setFullSyncJobState(FAILED);
      return;
    }
    throw new StateChangeConflictingException(getFullSyncJobState(), COMPLETED);
  }

  public Integer getNextPage() {
    return currentFullSyncPage.incrementAndGet();
  }

  public Integer getCurrentPage() {
    return getCurrentFullSyncPage();
  }
}
