/*
 * Copyright 2013-2014 the original author or authors.
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

package org.springframework.cloud.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.ParentContextApplicationContextInitializer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;

/**
 * A listener that prepares a SpringApplication (e.g. populating its Environment) by
 * delegating to {@link ApplicationContextInitializer} beans in a separate bootstrap
 * context. The bootstrap context is a SpringApplication created from sources defined in
 * spring.factories as {@link BootstrapConfiguration}, and initialized with external
 * config taken from "bootstrap.properties" (or yml), instead of the normal
 * "application.properties".
 * 
 * @author Dave Syer
 *
 */
public class BootstrapApplicationListener implements
		ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

	private int order = DEFAULT_ORDER;

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		Environment environment = event.getEnvironment();
		if (!environment.getProperty("spring.cloud.bootstrap.enabled", Boolean.class,
				true)) {
			return;
		}
		if (environment instanceof ConfigurableEnvironment) {
			ConfigurableEnvironment configurable = (ConfigurableEnvironment) environment;
			// don't listen to events in a bootstrap context
			if (configurable.getPropertySources().contains("bootstrapInProgress")) {
				return;
			}
			ConfigurableApplicationContext context = bootstrapServiceContext(
					configurable, event.getSpringApplication());
			apply(context, event.getSpringApplication(), configurable);
		}
	}

	private ConfigurableApplicationContext bootstrapServiceContext(
			ConfigurableEnvironment environment, final SpringApplication application) {
		StandardEnvironment bootstrapEnvironment = new StandardEnvironment();
		MutablePropertySources bootstrapProperties = bootstrapEnvironment
				.getPropertySources();
		for (PropertySource<?> source : bootstrapProperties) {
			bootstrapProperties.remove(source.getName());
		}
		bootstrapProperties.addFirst(new MapPropertySource("bootstrapInProgress",
				Collections.<String, Object> emptyMap()));
		for (PropertySource<?> source : environment.getPropertySources()) {
			bootstrapProperties.addLast(source);
		}
		bootstrapProperties.addFirst(new MapPropertySource("bootstrap", Collections
				.<String, Object> singletonMap("spring.config.name", "bootstrap")));
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		// Use names and ensure unique to protect against duplicates
		List<String> names = SpringFactoriesLoader.loadFactoryNames(
				BootstrapConfiguration.class, classLoader);
		// TODO: is it possible or sensible to share a ResourceLoader?
		SpringApplicationBuilder builder = new SpringApplicationBuilder()
				.showBanner(false).environment(bootstrapEnvironment).web(false)
				.properties("spring.application.name:bootstrap");
		List<Class<?>> sources = new ArrayList<Class<?>>();
		for (String name : names) {
			Class<?> cls = ClassUtils.resolveClassName(name, null);
			try {
				cls.getDeclaredAnnotations();
			} catch (Exception e) {
				continue;
			}
			if (cls != null) {
				sources.add(cls);
			}
		}
		builder.sources(sources.toArray());
		final ConfigurableApplicationContext context = builder.run();
		// Make the bootstrap context a parent of the app context
		addAncestorInitializer(application, context);
		bootstrapProperties.remove("bootstrapInProgress");
		return context;
	}

	private void addAncestorInitializer(SpringApplication application,
			ConfigurableApplicationContext context) {
		boolean installed = false;
		for (ApplicationContextInitializer<?> initializer : application.getInitializers()) {
			if (initializer instanceof AncestorInitializer) {
				installed = true;
				// New parent
				((AncestorInitializer) initializer).setParent(context);
			}
		}
		if (!installed) {
			application.addInitializers(new AncestorInitializer(context));
		}

	}

	private void apply(ConfigurableApplicationContext context,
			SpringApplication application, ConfigurableEnvironment environment) {
		@SuppressWarnings("rawtypes")
		List<ApplicationContextInitializer> initializers = getOrderedBeansOfType(context,
				ApplicationContextInitializer.class);
		application.addInitializers(initializers
				.toArray(new ApplicationContextInitializer[0]));
	}

	private <T> List<T> getOrderedBeansOfType(ListableBeanFactory context, Class<T> type) {
		List<T> result = new ArrayList<T>();
		for (String name : context.getBeanNamesForType(type)) {
			result.add(context.getBean(name, type));
		}
		AnnotationAwareOrderComparator.sort(result);
		return result;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	private static class AncestorInitializer implements
			ApplicationContextInitializer<ConfigurableApplicationContext> {

		private ConfigurableApplicationContext parent;

		public AncestorInitializer(ConfigurableApplicationContext parent) {
			this.parent = parent;
		}

		public void setParent(ConfigurableApplicationContext parent) {
			this.parent = parent;
		}

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			preemptMerge(context.getEnvironment().getPropertySources(), parent
					.getEnvironment().getPropertySources().get("bootstrap"));
			while (context.getParent() != null && context.getParent() != context) {
				context = (ConfigurableApplicationContext) context.getParent();
			}
			new ParentContextApplicationContextInitializer(parent).initialize(context);
		}

		private void preemptMerge(MutablePropertySources propertySources,
				PropertySource<?> propertySource) {
			if (propertySource != null
					&& !propertySources.contains(propertySource.getName())) {
				propertySources.addFirst(propertySource);
			}
		}

	}

}
