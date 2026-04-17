package com.eried.evendarkerbot.flic;

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
public final class FlicManager_Factory implements Factory<FlicManager> {
  private final Provider<Context> contextProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<TripRepository> tripRepositoryProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  public FlicManager_Factory(Provider<Context> contextProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<TripRepository> tripRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider) {
    this.contextProvider = contextProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.tripRepositoryProvider = tripRepositoryProvider;
    this.voiceServiceProvider = voiceServiceProvider;
  }

  @Override
  public FlicManager get() {
    return newInstance(contextProvider.get(), settingsRepositoryProvider.get(), wheelRepositoryProvider.get(), tripRepositoryProvider.get(), voiceServiceProvider.get());
  }

  public static FlicManager_Factory create(Provider<Context> contextProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<TripRepository> tripRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider) {
    return new FlicManager_Factory(contextProvider, settingsRepositoryProvider, wheelRepositoryProvider, tripRepositoryProvider, voiceServiceProvider);
  }

  public static FlicManager newInstance(Context context, SettingsRepository settingsRepository,
      WheelRepository wheelRepository, TripRepository tripRepository, VoiceService voiceService) {
    return new FlicManager(context, settingsRepository, wheelRepository, tripRepository, voiceService);
  }
}
