package com.eried.evendarkerbot.ui.dashboard;

import android.content.Context;
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
public final class DashboardViewModel_Factory implements Factory<DashboardViewModel> {
  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<TripRepository> tripRepositoryProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  private final Provider<Context> contextProvider;

  public DashboardViewModel_Factory(Provider<WheelRepository> wheelRepositoryProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<TripRepository> tripRepositoryProvider, Provider<VoiceService> voiceServiceProvider,
      Provider<Context> contextProvider) {
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.tripRepositoryProvider = tripRepositoryProvider;
    this.voiceServiceProvider = voiceServiceProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public DashboardViewModel get() {
    return newInstance(wheelRepositoryProvider.get(), settingsRepositoryProvider.get(), tripRepositoryProvider.get(), voiceServiceProvider.get(), contextProvider.get());
  }

  public static DashboardViewModel_Factory create(Provider<WheelRepository> wheelRepositoryProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<TripRepository> tripRepositoryProvider, Provider<VoiceService> voiceServiceProvider,
      Provider<Context> contextProvider) {
    return new DashboardViewModel_Factory(wheelRepositoryProvider, settingsRepositoryProvider, tripRepositoryProvider, voiceServiceProvider, contextProvider);
  }

  public static DashboardViewModel newInstance(WheelRepository wheelRepository,
      SettingsRepository settingsRepository, TripRepository tripRepository,
      VoiceService voiceService, Context context) {
    return new DashboardViewModel(wheelRepository, settingsRepository, tripRepository, voiceService, context);
  }
}
