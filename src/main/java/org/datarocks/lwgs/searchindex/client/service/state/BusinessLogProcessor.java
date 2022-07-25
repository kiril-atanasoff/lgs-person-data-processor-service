package org.datarocks.lwgs.searchindex.client.service.state;

import lombok.extern.slf4j.Slf4j;
import org.datarocks.lwgs.searchindex.client.entity.BusinessValidationLog;
import org.datarocks.lwgs.searchindex.client.repository.BusinessLogRepository;
import org.datarocks.lwgs.searchindex.client.service.amqp.CommonHeadersDao;
import org.datarocks.lwgs.searchindex.client.service.amqp.MessageCategory;
import org.datarocks.lwgs.searchindex.client.service.amqp.Queues;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class BusinessLogProcessor {
  private final BusinessLogRepository businessLogRepository;
  private final RabbitTemplate rabbitTemplate;

  @Autowired
  public BusinessLogProcessor(
      BusinessLogRepository businessLogRepository, RabbitTemplate rabbitTemplate) {
    this.businessLogRepository = businessLogRepository;
    this.rabbitTemplate = rabbitTemplate;
  }

  @Transactional
  protected void handleBusinessLogMessage(Message message) {
    try {
      final BusinessValidationLog log =
          (BusinessValidationLog) rabbitTemplate.getMessageConverter().fromMessage(message);
      businessLogRepository.save(log);
    } catch (ClassCastException exception) {
      log.error("Dropping wrongly encoded business log message.");
    }
  }

  @RabbitListener(queues = Queues.BUSINESS_LOG, concurrency = "1-2")
  protected void listen(Message message) {
    CommonHeadersDao headers = new CommonHeadersDao(message.getMessageProperties().getHeaders());
    if (headers
        .getOptionalMessageCategory()
        .orElse(MessageCategory.UNKNOWN)
        .equals(MessageCategory.BUSINESS_VALIDATION_LOG)) {
      handleBusinessLogMessage(message);
    }
  }
}