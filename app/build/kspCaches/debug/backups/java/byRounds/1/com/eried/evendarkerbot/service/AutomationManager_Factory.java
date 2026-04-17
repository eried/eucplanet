package com.eried.evendarkerbot.service;

import android.content.Context;
import com.eried.evendarkerbot.data.repository.TripRepository;
import com.eried.evendarkerbot.data.repository.WheelRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class AutomationManager_Factory implements Factory<AutomationManager> {
  private final Provider<Context> contextProvider;

  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<TripRepository> tripRepositoryProvider;

  public AutomationManager_Factory(Provider<Context> contextProvider,
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<TripRepository> tripRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.tripRepositoryProvider = tripRepositoryProvider;
  }

  @Override
  public AutomationManager get() {
    return newInstance(contextProvider.get(), wheelRepositoryProvider.get(), tripRepositoryProvider.get());
  }

  public static AutomationManager_Factory create(Provider<Context> contextProvider,
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<TripRepository> tripRepositoryProvider) {
    return new AutomationManager_Factory(contextProvider, wheelRepositoryProvider, tripRepositoryProvider);
  }

  public static AutomationManager newInstance(Context context, WheelRepository wheelRepository,
      TripRepository tripRepository) {
    return new AutomationManager(context, wheelRepository, tripRepository);
  }
}
