package com.eried.evendarkerbot.ui.settings;

import com.eried.evendarkerbot.data.repository.SettingsRepository;
import com.eried.evendarkerbot.flic.FlicManager;
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
public final class FlicViewModel_Factory implements Factory<FlicViewModel> {
  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<FlicManager> flicManagerProvider;

  public FlicViewModel_Factory(Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<FlicManager> flicManagerProvider) {
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.flicManagerProvider = flicManagerProvider;
  }

  @Override
  public FlicViewModel get() {
    return newInstance(settingsRepositoryProvider.get(), flicManagerProvider.get());
  }

  public static FlicViewModel_Factory create(
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<FlicManager> flicManagerProvider) {
    return new FlicViewModel_Factory(settingsRepositoryProvider, flicManagerProvider);
  }

  public static FlicViewModel newInstance(SettingsRepository settingsRepository,
      FlicManager flicManager) {
    return new FlicViewModel(settingsRepository, flicManager);
  }
}
