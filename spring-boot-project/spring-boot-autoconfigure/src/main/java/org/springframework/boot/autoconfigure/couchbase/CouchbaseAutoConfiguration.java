/*
 * Copyright 2012-2018 the original author or authors.
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

package org.springframework.boot.autoconfigure.couchbase;

import com.couchbase.client.core.env.KeyValueServiceConfig;
import com.couchbase.client.core.env.QueryServiceConfig;
import com.couchbase.client.core.env.ViewServiceConfig;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseBucket;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.cluster.ClusterInfo;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for Couchbase.
 *
 * @author Eddú Meléndez
 * @author Stephane Nicoll
 * @author Yulin Qin
 * @since 1.4.0
 */
@Configuration
@ConditionalOnClass({ CouchbaseBucket.class, Cluster.class })
@Conditional(CouchbaseAutoConfiguration.CouchbaseCondition.class)
@EnableConfigurationProperties(CouchbaseProperties.class)
public class CouchbaseAutoConfiguration {

	@Configuration
	@ConditionalOnMissingBean(value = CouchbaseConfiguration.class, type = "org.springframework.data.couchbase.config.CouchbaseConfigurer")
	public static class CouchbaseConfiguration {

		private final CouchbaseProperties properties;

		public CouchbaseConfiguration(CouchbaseProperties properties) {
			this.properties = properties;
		}

		@Bean
		@Primary
		public DefaultCouchbaseEnvironment couchbaseEnvironment() throws Exception {
			return initializeEnvironmentBuilder(this.properties).build();
		}

		@Bean
		@Primary
		public Cluster couchbaseCluster() throws Exception {
			return CouchbaseCluster.create(couchbaseEnvironment(),
					this.properties.getBootstrapHosts());
		}

		@Bean
		@Primary
		@DependsOn("couchbaseClient")
		public ClusterInfo couchbaseClusterInfo() throws Exception {
			return couchbaseCluster()
					.clusterManager(this.properties.getBucket().getName(),
							this.properties.getBucket().getPassword())
					.info();
		}

		@Bean
		@Primary
		public Bucket couchbaseClient() throws Exception {
			return couchbaseCluster().openBucket(this.properties.getBucket().getName(),
					this.properties.getBucket().getPassword());
		}

		/**
		 * Initialize an environment builder based on the specified settings.
		 * @param properties the couchbase properties to use
		 * @return the {@link DefaultCouchbaseEnvironment} builder.
		 */
		protected DefaultCouchbaseEnvironment.Builder initializeEnvironmentBuilder(
				CouchbaseProperties properties) {
			CouchbaseProperties.Endpoints endpoints = properties.getEnv().getEndpoints();
			CouchbaseProperties.Timeouts timeouts = properties.getEnv().getTimeouts();
			DefaultCouchbaseEnvironment.Builder builder = DefaultCouchbaseEnvironment
					.builder();
			if (timeouts.getConnect() != null) {
				builder = builder.connectTimeout(timeouts.getConnect().toMillis());
			}
			builder = builder.keyValueServiceConfig(KeyValueServiceConfig.create(
					endpoints.getKeyValue()));
			if (timeouts.getKeyValue() != null) {
				builder = builder.kvTimeout(timeouts.getKeyValue().toMillis());
			}
			CouchbaseServiceConfig queryConfig = determineCouchbaseServiceConfig(
					endpoints.getQueryservice(), endpoints.getQuery());
			builder = builder.queryServiceConfig(QueryServiceConfig.create(
					queryConfig.minEndpoints, queryConfig.maxEndpoints));
			if (timeouts.getQuery() != null) {
				CouchbaseServiceConfig viewConfig = determineCouchbaseServiceConfig(
						endpoints.getViewservice(), endpoints.getView());
				builder = builder.queryTimeout(timeouts.getQuery().toMillis())
						.viewServiceConfig(ViewServiceConfig.create(
								viewConfig.minEndpoints, viewConfig.maxEndpoints));
			}
			if (timeouts.getSocketConnect() != null) {
				builder = builder.socketConnectTimeout(
						(int) timeouts.getSocketConnect().toMillis());
			}
			if (timeouts.getView() != null) {
				builder = builder.viewTimeout(timeouts.getView().toMillis());
			}
			CouchbaseProperties.Ssl ssl = properties.getEnv().getSsl();
			if (ssl.getEnabled()) {
				builder.sslEnabled(true);
				if (ssl.getKeyStore() != null) {
					builder.sslKeystoreFile(ssl.getKeyStore());
				}
				if (ssl.getKeyStorePassword() != null) {
					builder.sslKeystorePassword(ssl.getKeyStorePassword());
				}
			}
			return builder;
		}

		private CouchbaseServiceConfig determineCouchbaseServiceConfig(
				CouchbaseProperties.Endpoints.CouchbaseService couchbaseService,
				Integer fallback) {
			if (couchbaseService.getMinEndpoints() != 1
					|| couchbaseService.getMaxEndpoints() != 1) {
				return new CouchbaseServiceConfig(couchbaseService.getMinEndpoints(),
						couchbaseService.getMaxEndpoints());
			}
			int endpoints = (fallback != null ? fallback : 1);
			return new CouchbaseServiceConfig(endpoints, endpoints);
		}

		private static class CouchbaseServiceConfig {
			private int minEndpoints;
			private int maxEndpoints;

			CouchbaseServiceConfig(int minEndpoints, int maxEndpoints) {
				this.minEndpoints = minEndpoints;
				this.maxEndpoints = maxEndpoints;
			}
		}

	}

	/**
	 * Determine if Couchbase should be configured. This happens if either the
	 * user-configuration defines a {@code CouchbaseConfigurer} or if at least the
	 * "bootstrapHosts" property is specified.
	 * <p>
	 * The reason why we check for the presence of {@code CouchbaseConfigurer} is that it
	 * might use {@link CouchbaseProperties} for its internal customization.
	 */
	static class CouchbaseCondition extends AnyNestedCondition {

		CouchbaseCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@Conditional(OnBootstrapHostsCondition.class)
		static class BootstrapHostsProperty {

		}

		@ConditionalOnBean(type = "org.springframework.data.couchbase.config.CouchbaseConfigurer")
		static class CouchbaseConfigurerAvailable {

		}

	}

}
