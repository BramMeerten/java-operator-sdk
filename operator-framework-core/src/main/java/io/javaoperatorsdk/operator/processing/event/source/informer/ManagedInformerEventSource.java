package io.javaoperatorsdk.operator.processing.event.source.informer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.api.config.ConfigurationService;
import io.javaoperatorsdk.operator.api.config.NamespaceChangeable;
import io.javaoperatorsdk.operator.api.config.ResourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.dependent.RecentOperationCacheFiller;
import io.javaoperatorsdk.operator.health.InformerHealthIndicator;
import io.javaoperatorsdk.operator.health.InformerWrappingEventSourceHealthIndicator;
import io.javaoperatorsdk.operator.health.Status;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.AbstractResourceEventSource;
import io.javaoperatorsdk.operator.processing.event.source.Cache;
import io.javaoperatorsdk.operator.processing.event.source.Configurable;
import io.javaoperatorsdk.operator.processing.event.source.IndexerResourceCache;

public abstract class ManagedInformerEventSource<R extends HasMetadata, P extends HasMetadata, C extends ResourceConfiguration<R>>
    extends AbstractResourceEventSource<R, P>
    implements ResourceEventHandler<R>, Cache<R>, IndexerResourceCache<R>,
    RecentOperationCacheFiller<R>, NamespaceChangeable,
    InformerWrappingEventSourceHealthIndicator<R>, Configurable<C> {

  private static final Logger log = LoggerFactory.getLogger(ManagedInformerEventSource.class);
  private final InformerManager<R, C> cache;

  protected TemporaryResourceCache<R> temporaryResourceCache;
  protected MixedOperation<R, KubernetesResourceList<R>, Resource<R>> client;

  protected ManagedInformerEventSource(
      MixedOperation<R, KubernetesResourceList<R>, Resource<R>> client, C configuration) {
    super(configuration.getResourceClass());
    this.client = client;
    temporaryResourceCache = new TemporaryResourceCache<>(this);
    this.cache = new InformerManager<>(client, configuration, this);
  }

  @Override
  public void onAdd(R resource) {
    temporaryResourceCache.removeResourceFromCache(resource);
  }

  @Override
  public void onUpdate(R oldObj, R newObj) {
    temporaryResourceCache.removeResourceFromCache(newObj);
  }

  @Override
  public void onDelete(R obj, boolean deletedFinalStateUnknown) {
    temporaryResourceCache.removeResourceFromCache(obj);
  }

  protected InformerManager<R, C> manager() {
    return cache;
  }

  @Override
  public void changeNamespaces(Set<String> namespaces) {
    if (allowsNamespaceChanges()) {
      manager().changeNamespaces(namespaces);
    }
  }

  @Override
  public void start() {
    manager().start();
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    manager().stop();
  }

  @Override
  public void handleRecentResourceUpdate(ResourceID resourceID, R resource,
      R previousVersionOfResource) {
    temporaryResourceCache.putUpdatedResource(resource,
        previousVersionOfResource.getMetadata().getResourceVersion());
  }

  @Override
  public void handleRecentResourceCreate(ResourceID resourceID, R resource) {
    temporaryResourceCache.putAddedResource(resource);
  }

  @Override
  public Optional<R> get(ResourceID resourceID) {
    Optional<R> resource = temporaryResourceCache.getResourceFromCache(resourceID);
    if (resource.isPresent()) {
      log.debug("Resource found in temporary cache for Resource ID: {}", resourceID);
      return resource;
    } else {
      log.debug("Resource not found in temporary cache reading it from informer cache," +
          " for Resource ID: {}", resourceID);
      var res = cache.get(resourceID);
      log.debug("Resource found in cache: {} for id: {}", res.isPresent(), resourceID);
      return res;
    }
  }

  public Optional<R> getCachedValue(ResourceID resourceID) {
    return get(resourceID);
  }

  @Override
  public Stream<R> list(String namespace, Predicate<R> predicate) {
    return manager().list(namespace, predicate);
  }

  void setTemporalResourceCache(TemporaryResourceCache<R> temporaryResourceCache) {
    this.temporaryResourceCache = temporaryResourceCache;
  }

  public void addIndexers(Map<String, Function<R, List<String>>> indexers) {
    cache.addIndexers(indexers);
  }

  public List<R> byIndex(String indexName, String indexKey) {
    return manager().byIndex(indexName, indexKey);
  }

  @Override
  public Stream<ResourceID> keys() {
    return cache.keys();
  }

  @Override
  public Stream<R> list(Predicate<R> predicate) {
    return cache.list(predicate);
  }

  @Override
  public Map<String, InformerHealthIndicator> informerHealthIndicators() {
    return cache.informerHealthIndicators();
  }

  @Override
  public Status getStatus() {
    return InformerWrappingEventSourceHealthIndicator.super.getStatus();
  }

  @Override
  public ResourceConfiguration<R> getInformerConfiguration() {
    return configuration();
  }

  @Override
  public C configuration() {
    return manager().configuration();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "resourceClass: " + configuration().getResourceClass().getSimpleName() +
        "}";
  }

  public void setConfigurationService(ConfigurationService configurationService) {
    cache.setConfigurationService(configurationService);
  }
}
