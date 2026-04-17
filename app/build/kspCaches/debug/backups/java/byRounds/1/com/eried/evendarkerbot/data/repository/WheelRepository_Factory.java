package com.eried.evendarkerbot.data.repository;

import android.content.Context;
import com.eried.evendarkerbot.ble.BleConnectionManager;
import com.eried.evendarkerbot.service.AlarmEngine;
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
public final class WheelRepository_Factory implements Factory<WheelRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<BleConnectionManager> bleManagerProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<AlarmEngine> alarmEngineProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  public WheelRepository_Factory(Provider<Context> contextProvider,
      Provider<BleConnectionManager> bleManagerProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<AlarmEngine> alarmEngineProvider, Provider<VoiceService> voiceServiceProvider) {
    this.contextProvider = contextProvider;
    this.bleManagerProvider = bleManagerProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.alarmEngineProvider = alarmEngineProvider;
    this.voiceServiceProvider = voiceServiceProvider;
  }

  @Override
  public WheelRepository get() {
    return newInstance(contextProvider.get(), bleManagerProvider.get(), settingsRepositoryProvider.get(), alarmEngineProvider.get(), voiceServiceProvider.get());
  }

  public static WheelRepository_Factory create(Provider<Context> contextProvider,
      Provider<BleConnectionManager> bleManagerProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<AlarmEngine> alarmEngineProvider, Provider<VoiceService> voiceServiceProvider) {
    return new WheelRepository_Factory(contextProvider, bleManagerProvider, settingsRepositoryProvider, alarmEngineProvider, voiceServiceProvider);
  }

  public static WheelRepository newInstance(Context context, BleConnectionManager bleManager,
      SettingsRepository settingsRepository, AlarmEngine alarmEngine, VoiceService voiceService) {
    return new WheelRepository(context, bleManager, settingsRepository, alarmEngine, voiceService);
  }
}
