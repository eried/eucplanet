package com.eried.evendarkerbot.ui.dashboard;

import com.eried.evendarkerbot.data.repository.SettingsRepository;
import com.eried.evendarkerbot.data.repository.WheelRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
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
public final class MetricDetailViewModel_Factory implements Factory<MetricDetailViewModel> {
  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  public MetricDetailViewModel_Factory(Provider<WheelRepository> wheelRepositoryProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  @Override
  public MetricDetailViewModel get() {
    return newInstance(wheelRepositoryProvider.get(), settingsRepositoryProvider.get());
  }

  public static MetricDetailViewModel_Factory create(
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    return new MetricDetailViewModel_Factory(wheelRepositoryProvider, settingsRepositoryProvider);
  }

  public static MetricDetailViewModel newInstance(WheelRepository wheelRepository,
      SettingsRepository settingsRepository) {
    return new MetricDetailViewModel(wheelRepository, settingsRepository);
  }
}
