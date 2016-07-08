/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kafka;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.I0Itec.zkclient.ZkClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.Utils;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.BinderException;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.ExtendedPropertiesBinder;
import org.springframework.cloud.stream.binder.PartitionHandler;
import org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.config.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.config.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.config.KafkaProducerProperties;
import org.springframework.context.Lifecycle;
import org.springframework.integration.endpoint.AbstractEndpoint;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.config.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.TopicPartitionInitialOffset;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryOperations;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import kafka.admin.AdminUtils;
import kafka.api.TopicMetadata;
import kafka.common.ErrorMapping;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;
import scala.collection.Seq;

/**
 * A {@link Binder} that uses Kafka as the underlying middleware.
 * @author Eric Bottard
 * @author Marius Bogoevici
 * @author Ilayaperumal Gopinathan
 * @author David Turanski
 * @author Gary Russell
 * @author Mark Fisher
 * @author Soby Chacko
 */
public class KafkaMessageChannelBinder extends
		AbstractMessageChannelBinder<ExtendedConsumerProperties<KafkaConsumerProperties>,
				ExtendedProducerProperties<KafkaProducerProperties>>
		implements ExtendedPropertiesBinder<MessageChannel, KafkaConsumerProperties, KafkaProducerProperties>,
		DisposableBean {

	private static final ByteArraySerializer BYTE_ARRAY_SERIALIZER = new ByteArraySerializer();

	private static final ThreadFactory DAEMON_THREAD_FACTORY;

	static {
		CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("kafka-binder-");
		threadFactory.setDaemon(true);
		DAEMON_THREAD_FACTORY = threadFactory;
	}

	private final KafkaBinderConfigurationProperties configurationProperties;

	private RetryOperations metadataRetryOperations;

	private final Map<String, Collection<PartitionInfo>> topicsInUse = new HashMap<>();

	// -------- Default values for properties -------

	private ConsumerFactory<byte[], byte[]> consumerFactory;

	private ProducerListener producerListener;

	private volatile Producer<byte[], byte[]> dlqProducer;

	private KafkaExtendedBindingProperties extendedBindingProperties = new KafkaExtendedBindingProperties();

	public KafkaMessageChannelBinder(KafkaBinderConfigurationProperties configurationProperties) {
		super(false, headersToMap(configurationProperties));
		this.configurationProperties = configurationProperties;
	}

	private static String[] headersToMap(KafkaBinderConfigurationProperties configurationProperties) {
		String[] headersToMap;
		if (ObjectUtils.isEmpty(configurationProperties.getHeaders())) {
			headersToMap = BinderHeaders.STANDARD_HEADERS;
		}
		else {
			String[] combinedHeadersToMap = Arrays.copyOfRange(BinderHeaders.STANDARD_HEADERS, 0,
					BinderHeaders.STANDARD_HEADERS.length + configurationProperties.getHeaders().length);
			System.arraycopy(configurationProperties.getHeaders(), 0, combinedHeadersToMap,
					BinderHeaders.STANDARD_HEADERS.length,
					configurationProperties.getHeaders().length);
			headersToMap = combinedHeadersToMap;
		}
		return headersToMap;
	}

	/**
	 * Retry configuration for operations such as validating topic creation
	 * @param metadataRetryOperations the retry configuration
	 */
	public void setMetadataRetryOperations(RetryOperations metadataRetryOperations) {
		this.metadataRetryOperations = metadataRetryOperations;
	}

	public void setExtendedBindingProperties(KafkaExtendedBindingProperties extendedBindingProperties) {
		this.extendedBindingProperties = extendedBindingProperties;
	}

	@Override
	public void onInit() throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, configurationProperties.getKafkaConnectionString());
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, configurationProperties.getConsumerGroup());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
				"earliest");
		Deserializer<byte[]> valueDecoder = new ByteArrayDeserializer();
		Deserializer<byte[]> keyDecoder = new ByteArrayDeserializer();

		consumerFactory = new DefaultKafkaConsumerFactory<>(props, keyDecoder, valueDecoder);

		if (metadataRetryOperations == null) {
			RetryTemplate retryTemplate = new RetryTemplate();

			SimpleRetryPolicy simpleRetryPolicy = new SimpleRetryPolicy();
			simpleRetryPolicy.setMaxAttempts(10);
			retryTemplate.setRetryPolicy(simpleRetryPolicy);

			ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
			backOffPolicy.setInitialInterval(100);
			backOffPolicy.setMultiplier(2);
			backOffPolicy.setMaxInterval(1000);
			retryTemplate.setBackOffPolicy(backOffPolicy);
			metadataRetryOperations = retryTemplate;
		}
	}

	@Override
	public void destroy() throws Exception {
		if (this.dlqProducer != null) {
			this.dlqProducer.close();
			this.dlqProducer = null;
		}
	}

	public void setProducerListener(ProducerListener producerListener) {
		this.producerListener = producerListener;
	}

	/**
	 * Allowed chars are ASCII alphanumerics, '.', '_' and '-'.
	 */
	static void validateTopicName(String topicName) {
		try {
			byte[] utf8 = topicName.getBytes("UTF-8");
			for (byte b : utf8) {
				if (!((b >= 'a') && (b <= 'z') || (b >= 'A') && (b <= 'Z') || (b >= '0') && (b <= '9') || (b == '.')
						|| (b == '-') || (b == '_'))) {
					throw new IllegalArgumentException(
							"Topic name can only have ASCII alphanumerics, '.', '_' and '-'");
				}
			}
		}
		catch (UnsupportedEncodingException e) {
			throw new AssertionError(e); // Can't happen
		}
	}

	@Override
	public KafkaConsumerProperties getExtendedConsumerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedConsumerProperties(channelName);
	}

	@Override
	public KafkaProducerProperties getExtendedProducerProperties(String channelName) {
		return this.extendedBindingProperties.getExtendedProducerProperties(channelName);
	}

	Map<String, Collection<PartitionInfo>> getTopicsInUse() {
		return this.topicsInUse;
	}

	@Override
	protected Object createConsumerDestinationIfNecessary(String name, String group,
														  ExtendedConsumerProperties<KafkaConsumerProperties> properties) {
		validateTopicName(name);
		if (properties.getInstanceCount() == 0) {
			throw new IllegalArgumentException("Instance count cannot be zero");
		}
		Collection<PartitionInfo> allPartitions = ensureTopicCreated(name,
				properties.getInstanceCount() * properties.getConcurrency());

		Collection<PartitionInfo> listenedPartitions;

		if (properties.getInstanceCount() == 1) {
			listenedPartitions = allPartitions;
		}
		else {
			listenedPartitions = new ArrayList<>();
			for (PartitionInfo partition : allPartitions) {
				// divide partitions across modules
				if ((partition.partition() % properties.getInstanceCount()) == properties.getInstanceIndex()) {
					listenedPartitions.add(partition);
				}
			}
		}
		this.topicsInUse.put(name, listenedPartitions);
		return listenedPartitions;
	}


	@Override
	@SuppressWarnings("unchecked")
	protected AbstractEndpoint createConsumerEndpoint(String name, String group, Object queue,
													  MessageChannel inputChannel, ExtendedConsumerProperties<KafkaConsumerProperties> properties) {

		Collection<PartitionInfo> listenedPartitions = (Collection<PartitionInfo>) queue;
		Assert.isTrue(!CollectionUtils.isEmpty(listenedPartitions), "A list of partitions must be provided");

		final TopicPartitionInitialOffset[] topicPartitionInitialOffsets =
				new TopicPartitionInitialOffset[listenedPartitions.size()];
		int i = 0;
		for (PartitionInfo partition : listenedPartitions){
			topicPartitionInitialOffsets[i++] = new TopicPartitionInitialOffset(partition.topic(),
					partition.partition());
		}


		int concurrency = Math.min(properties.getConcurrency(), listenedPartitions.size());

		final ExecutorService dispatcherTaskExecutor =
				Executors.newFixedThreadPool(concurrency, DAEMON_THREAD_FACTORY);

		final ContainerProperties containerProperties = new ContainerProperties(topicPartitionInitialOffsets);
		//final ContainerProperties containerProperties = new ContainerProperties(name);
		final ConcurrentMessageListenerContainer<byte[], byte[]> messageListenerContainer = new ConcurrentMessageListenerContainer<>(
				consumerFactory, containerProperties);
		messageListenerContainer.setConcurrency(concurrency);

		if (logger.isDebugEnabled()) {
			logger.debug("Listened partitions: " + StringUtils.collectionToCommaDelimitedString(listenedPartitions));
		}

		if (this.logger.isDebugEnabled()) {
			this.logger.debug(
					"Listened partitions: " + StringUtils.collectionToCommaDelimitedString(listenedPartitions));
		}

		final KafkaMessageDrivenChannelAdapter<byte[], byte[]> kafkaMessageDrivenChannelAdapter = new KafkaMessageDrivenChannelAdapter<>(
				messageListenerContainer);

		kafkaMessageDrivenChannelAdapter.setBeanFactory(this.getBeanFactory());
		kafkaMessageDrivenChannelAdapter.setOutputChannel(inputChannel);
		kafkaMessageDrivenChannelAdapter.afterPropertiesSet();

		// we need to wrap the adapter listener into a retrying listener so that the retry
		// logic is applied before the ErrorHandler is executed
		final RetryTemplate retryTemplate = buildRetryTemplateIfRetryEnabled(properties);
		if (retryTemplate != null) {
			if (properties.getExtension().isAutoCommitOffset()) {
				final MessageListener originalMessageListener = (MessageListener) messageListenerContainer
						.getContainerProperties().getMessageListener();
				messageListenerContainer.getContainerProperties().setMessageListener(new MessageListener() {
					@Override
					public void onMessage(final ConsumerRecord message) {
						try {
							retryTemplate.execute(new RetryCallback<Object, Throwable>() {
								@Override
								public Object doWithRetry(RetryContext context) {
									originalMessageListener.onMessage(message);
									return null;
								}
							});
						}
						catch (Throwable throwable) {
							if (throwable instanceof RuntimeException) {
								throw (RuntimeException) throwable;
							}
							else {
								throw new RuntimeException(throwable);
							}
						}
					}
				});
			}
			else {
				messageListenerContainer.getContainerProperties().setMessageListener(new AcknowledgingMessageListener() {
					final AcknowledgingMessageListener originalMessageListener = (AcknowledgingMessageListener) messageListenerContainer
							.getContainerProperties().getMessageListener();

					@Override
					public void onMessage(final ConsumerRecord message, final Acknowledgment acknowledgment) {
						retryTemplate.execute(new RetryCallback<Object, RuntimeException>() {
							@Override
							public Object doWithRetry(RetryContext context) {
								originalMessageListener.onMessage(message, acknowledgment);
								return null;
							}
						});
					}
				});
			}
		}

		if (properties.getExtension().isEnableDlq()) {
			final String dlqTopic = "error." + name + "." + group;
			initDlqProducer();
			messageListenerContainer.getContainerProperties().setErrorHandler(new ErrorHandler() {
				@Override
				public void handle(Exception thrownException, final ConsumerRecord message) {
					final byte[] key = message.key() != null ? Utils.toArray(ByteBuffer.wrap((byte[])message.key()))
							: null;
					final byte[] payload = message.value() != null
							? Utils.toArray(ByteBuffer.wrap((byte[])message.value())) : null;
					dlqProducer.send(new ProducerRecord<>(dlqTopic, key, payload), new Callback() {
						@Override
						public void onCompletion(RecordMetadata metadata, Exception exception) {
							StringBuffer messageLog = new StringBuffer();
							messageLog.append(" a message with key='"
									+ toDisplayString(ObjectUtils.nullSafeToString(key), 50) + "'");
							messageLog.append(" and payload='"
									+ toDisplayString(ObjectUtils.nullSafeToString(payload), 50) + "'");
							messageLog.append(" received from " + message.partition());
							if (exception != null) {
								logger.error("Error sending to DLQ" + messageLog.toString(), exception);
							}
							else {
								if (logger.isDebugEnabled()) {
									logger.debug("Sent to DLQ " + messageLog.toString());
								}
							}
						}
					});
				}
			});
		}


		kafkaMessageDrivenChannelAdapter.start();
		return kafkaMessageDrivenChannelAdapter;
	}

	@Override
	protected MessageHandler createProducerMessageHandler(final String name,
														  ExtendedProducerProperties<KafkaProducerProperties> producerProperties) throws Exception {

		validateTopicName(name);

		Collection<PartitionInfo> partitions = ensureTopicCreated(name, producerProperties.getPartitionCount());

		if (producerProperties.getPartitionCount() < partitions.size()) {
			if (logger.isInfoEnabled()) {
				logger.info("The `partitionCount` of the producer for topic " + name + " is "
						+ producerProperties.getPartitionCount() + ", smaller than the actual partition count of "
						+ partitions.size() + " of the topic. The larger number will be used instead.");
			}
		}

		topicsInUse.put(name, partitions);

		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configurationProperties.getKafkaConnectionString());
		props.put(ProducerConfig.RETRIES_CONFIG, 0);
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		props.put(ProducerConfig.ACKS_CONFIG, String.valueOf(configurationProperties.getRequiredAcks()));
		props.put(ProducerConfig.LINGER_MS_CONFIG,
				String.valueOf(producerProperties.getExtension().getBatchTimeout()));
		props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, producerProperties.getExtension().getCompressionType().toString());

		ProducerFactory<byte[], byte[]> producerFB = new DefaultKafkaProducerFactory<>(props);

//		final ProducerConfiguration<byte[], byte[]> producerConfiguration = new ProducerConfiguration<>(
//				producerMetadata, producerFB.getObject());
//		producerConfiguration.setProducerListener(this.producerListener);
//		KafkaProducerContext kafkaProducerContext = new KafkaProducerContext();
//		kafkaProducerContext.setProducerConfigurations(
//				Collections.<String, ProducerConfiguration<?, ?>>singletonMap(name, producerConfiguration));
//		return new ProducerConfigurationMessageHandler(producerConfiguration, name);
//
		return new SendingHandler(producerFB, name, producerProperties, partitions.size());
	}

	@Override
	protected void createProducerDestinationIfNecessary(String name,
														ExtendedProducerProperties<KafkaProducerProperties> properties) {
		if (this.logger.isInfoEnabled()) {
			this.logger.info("Using kafka topic for outbound: " + name);
		}
		validateTopicName(name);
		Collection<PartitionInfo> partitions = ensureTopicCreated(name, properties.getPartitionCount());
		if (properties.getPartitionCount() < partitions.size()) {
			if (this.logger.isInfoEnabled()) {
				this.logger.info("The `partitionCount` of the producer for topic " + name + " is "
						+ properties.getPartitionCount() + ", smaller than the actual partition count of "
						+ partitions.size() + " of the topic. The larger number will be used instead.");
			}
		}
		this.topicsInUse.put(name, partitions);
	}

	/**
	 * Creates a Kafka topic if needed, or try to increase its partition count to the
	 * desired number.
	 */
	private Collection<PartitionInfo> ensureTopicCreated(final String topicName, final int partitionCount) {

		final ZkClient zkClient = new ZkClient(configurationProperties.getZkConnectionString(),
				configurationProperties.getZkSessionTimeout(), configurationProperties.getZkConnectionTimeout(),
				ZKStringSerializer$.MODULE$);

		final ZkUtils zkUtils = new ZkUtils(zkClient, null, false);
		try {
			final Properties topicConfig = new Properties();
			TopicMetadata topicMetadata = AdminUtils.fetchTopicMetadataFromZk(topicName, zkUtils);
			if (topicMetadata.errorCode() == ErrorMapping.NoError()) {
				// only consider minPartitionCount for resizing if autoAddPartitions is
				// true
				int effectivePartitionCount = configurationProperties.isAutoAddPartitions()
						? Math.max(configurationProperties.getMinPartitionCount(), partitionCount) : partitionCount;
				if (topicMetadata.partitionsMetadata().size() < effectivePartitionCount) {
					if (configurationProperties.isAutoAddPartitions()) {
						AdminUtils.addPartitions(zkUtils, topicName, effectivePartitionCount, null, false);
					}
					else {
						int topicSize = topicMetadata.partitionsMetadata().size();
						throw new BinderException("The number of expected partitions was: " + partitionCount + ", but "
								+ topicSize + (topicSize > 1 ? " have " : " has ") + "been found instead."
								+ "Consider either increasing the partition count of the topic or enabling `autoAddPartitions`");
					}
				}
			}
			else if (topicMetadata.errorCode() == ErrorMapping.UnknownTopicOrPartitionCode()) {
				if (configurationProperties.isAutoCreateTopics()) {
					Seq<Object> brokerList = zkUtils.getSortedBrokerList();
					// always consider minPartitionCount for topic creation
					int effectivePartitionCount = Math.max(configurationProperties.getMinPartitionCount(),
							partitionCount);
					final scala.collection.Map<Object, Seq<Object>> replicaAssignment = AdminUtils
							.assignReplicasToBrokers(brokerList, effectivePartitionCount,
									configurationProperties.getReplicationFactor(), -1, -1);
					metadataRetryOperations.execute(new RetryCallback<Object, RuntimeException>() {
						@Override
						public Object doWithRetry(RetryContext context) throws RuntimeException {
							AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topicName,
									replicaAssignment, topicConfig, true);
							return null;
						}
					});
				}
				else {
					throw new BinderException("Topic " + topicName + " does not exist");
				}
			}
			else {
				throw new BinderException("Error fetching Kafka topic metadata: ",
						ErrorMapping.exceptionFor(topicMetadata.errorCode()));
			}
			try {
				Collection<PartitionInfo> partitions = metadataRetryOperations
						.execute(new RetryCallback<Collection<PartitionInfo>, Exception>() {

							@Override
							public Collection<PartitionInfo> doWithRetry(RetryContext context) throws Exception {
								Collection<PartitionInfo> partitions = consumerFactory.createConsumer().partitionsFor(topicName);
								// do a sanity check on the partition set
								if (partitions.size() < partitionCount) {
									throw new IllegalStateException("The number of expected partitions was: "
											+ partitionCount + ", but " + partitions.size()
											+ (partitions.size() > 1 ? " have " : " has ") + "been found instead");
								}
								return partitions;
							}
						});
				return partitions;
			}
			catch (Exception e) {
				logger.error("Cannot initialize Binder", e);
				throw new BinderException("Cannot initialize binder:", e);
			}

		}
		finally {
			zkClient.close();
		}
	}

	private synchronized void initDlqProducer() {
		try {
			if (dlqProducer == null) {
				synchronized (this) {
					if (dlqProducer == null) {
						// we can use the producer defaults as we do not need to tune
						// performance
						Map<String, Object> props = new HashMap<>();
						props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configurationProperties.getKafkaConnectionString());
						props.put(ProducerConfig.RETRIES_CONFIG, 0);
						props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
						props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
						props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
						props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
						props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
						DefaultKafkaProducerFactory<byte[], byte[]> defaultKafkaProducerFactory = new DefaultKafkaProducerFactory<>(props);
						dlqProducer = defaultKafkaProducerFactory.createProducer();
					}
				}
			}
		}
		catch (Exception e) {
			throw new RuntimeException("Cannot initialize DLQ producer:", e);
		}
	}

	private String toDisplayString(String original, int maxCharacters) {
		if (original.length() <= maxCharacters) {
			return original;
		}
		return original.substring(0, maxCharacters) + "...";
	}

	@Override
	public void doManualAck(LinkedList<MessageHeaders> messageHeadersList) {
		Iterator<MessageHeaders> iterator = messageHeadersList.iterator();
		while (iterator.hasNext()) {
			MessageHeaders headers = iterator.next();
			Acknowledgment acknowledgment = (Acknowledgment) headers.get(KafkaHeaders.ACKNOWLEDGMENT);
			Assert.notNull(acknowledgment,
					"Acknowledgement shouldn't be null when acknowledging kafka message " + "manually.");
			acknowledgment.acknowledge();
		}
	}

	private final class SendingHandler extends AbstractMessageHandler implements Lifecycle {

		private final AtomicInteger roundRobinCount = new AtomicInteger();

		private ProducerFactory<byte[], byte[]> delegate;

		private String targetTopic;
		private final ExtendedProducerProperties<KafkaProducerProperties> producerProperties;
		private final PartitionHandler partitionHandler;
		private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
		private final int numberOfKafkaPartitions;

		private SendingHandler(
				ProducerFactory<byte[], byte[]> delegate, String targetTopic,
				ExtendedProducerProperties<KafkaProducerProperties> producerProperties,
				int numberOfPartitions) {
			Assert.notNull(delegate, "Delegate cannot be null");
			Assert.hasText(targetTopic, "Target topic cannot be null");
			this.delegate = delegate;
			this.targetTopic = targetTopic;
			this.producerProperties = producerProperties;
			ConfigurableListableBeanFactory beanFactory = KafkaMessageChannelBinder.this.getBeanFactory();
			this.setBeanFactory(beanFactory);
			this.numberOfKafkaPartitions = numberOfPartitions;
			this.kafkaTemplate = new KafkaTemplate<>(delegate);

			this.partitionHandler = new PartitionHandler(beanFactory, evaluationContext, partitionSelector, producerProperties);
		}

		@Override
		public void handleMessageInternal(Message<?> message) throws MessagingException {
			int targetPartition;
			if (producerProperties.isPartitioned()) {
				targetPartition = this.partitionHandler.determinePartition(message);
			}
			else {
				targetPartition = roundRobin() % numberOfKafkaPartitions;
			}
//			this.delegate.send(this.targetTopic,
//					message.getHeaders().get(BinderHeaders.PARTITION_HEADER, Integer.class), null,
//					(byte[]) message.getPayload());

			kafkaTemplate.send(this.targetTopic, targetPartition, (byte[]) message.getPayload());
		}

		private int roundRobin() {
			int result = roundRobinCount.incrementAndGet();
			if (result == Integer.MAX_VALUE) {
				roundRobinCount.set(0);
			}
			return result;
		}


		@Override
		public void start() {

		}

		@Override
		public void stop() {

		}

		@Override
		public boolean isRunning() {
			return false;
		}
	}
}