package com.eried.evendarkerbot.service;

import android.content.Context;
import com.eried.evendarkerbot.data.db.AlarmDao;
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
public final class AlarmEngine_Factory implements Factory<AlarmEngine> {
  private final Provider<Context> contextProvider;

  private final Provider<AlarmDao> alarmDaoProvider;

  private final Provider<TonePlayer> tonePlayerProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  public AlarmEngine_Factory(Provider<Context> contextProvider, Provider<AlarmDao> alarmDaoProvider,
      Provider<TonePlayer> tonePlayerProvider, Provider<VoiceService> voiceServiceProvider) {
    this.contextProvider = contextProvider;
    this.alarmDaoProvider = alarmDaoProvider;
    this.tonePlayerProvider = tonePlayerProvider;
    this.voiceServiceProvider = voiceServiceProvider;
  }

  @Override
  public AlarmEngine get() {
    return newInstance(contextProvider.get(), alarmDaoProvider.get(), tonePlayerProvider.get(), voiceServiceProvider.get());
  }

  public static AlarmEngine_Factory create(Provider<Context> contextProvider,
      Provider<AlarmDao> alarmDaoProvider, Provider<TonePlayer> tonePlayerProvider,
      Provider<VoiceService> voiceServiceProvider) {
    return new AlarmEngine_Factory(contextProvider, alarmDaoProvider, tonePlayerProvider, voiceServiceProvider);
  }

  public static AlarmEngine newInstance(Context context, AlarmDao alarmDao, TonePlayer tonePlayer,
      VoiceService voiceService) {
    return new AlarmEngine(context, alarmDao, tonePlayer, voiceService);
  }
}
