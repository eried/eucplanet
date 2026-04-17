package com.eried.evendarkerbot.ui.settings;

import com.eried.evendarkerbot.data.repository.SettingsRepository;
import com.eried.evendarkerbot.data.repository.TripRepository;
import com.eried.evendarkerbot.data.repository.WheelRepository;
import com.eried.evendarkerbot.service.VoiceService;
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  private final Provider<TripRepository> tripRepositoryProvider;

  public SettingsViewModel_Factory(Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider,
      Provider<TripRepository> tripRepositoryProvider) {
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.voiceServiceProvider = voiceServiceProvider;
    this.tripRepositoryProvider = tripRepositoryProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(settingsRepositoryProvider.get(), wheelRepositoryProvider.get(), voiceServiceProvider.get(), tripRepositoryProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider,
      Provider<TripRepository> tripRepositoryProvider) {
    return new SettingsViewModel_Factory(settingsRepositoryProvider, wheelRepositoryProvider, voiceServiceProvider, tripRepositoryProvider);
  }

  public static SettingsViewModel newInstance(SettingsRepository settingsRepository,
      WheelRepository wheelRepository, VoiceService voiceService, TripRepository tripRepository) {
    return new SettingsViewModel(settingsRepository, wheelRepository, voiceService, tripRepository);
  }
}
