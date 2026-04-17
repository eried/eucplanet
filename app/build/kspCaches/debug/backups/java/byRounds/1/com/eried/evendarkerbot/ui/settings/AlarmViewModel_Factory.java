package com.eried.evendarkerbot.ui.settings;

import android.content.Context;
import com.eried.evendarkerbot.data.db.AlarmDao;
import com.eried.evendarkerbot.service.TonePlayer;
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
public final class AlarmViewModel_Factory implements Factory<AlarmViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<AlarmDao> alarmDaoProvider;

  private final Provider<TonePlayer> tonePlayerProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  public AlarmViewModel_Factory(Provider<Context> contextProvider,
      Provider<AlarmDao> alarmDaoProvider, Provider<TonePlayer> tonePlayerProvider,
      Provider<VoiceService> voiceServiceProvider) {
    this.contextProvider = contextProvider;
    this.alarmDaoProvider = alarmDaoProvider;
    this.tonePlayerProvider = tonePlayerProvider;
    this.voiceServiceProvider = voiceServiceProvider;
  }

  @Override
  public AlarmViewModel get() {
    return newInstance(contextProvider.get(), alarmDaoProvider.get(), tonePlayerProvider.get(), voiceServiceProvider.get());
  }

  public static AlarmViewModel_Factory create(Provider<Context> contextProvider,
      Provider<AlarmDao> alarmDaoProvider, Provider<TonePlayer> tonePlayerProvider,
      Provider<VoiceService> voiceServiceProvider) {
    return new AlarmViewModel_Factory(contextProvider, alarmDaoProvider, tonePlayerProvider, voiceServiceProvider);
  }

  public static AlarmViewModel newInstance(Context context, AlarmDao alarmDao,
      TonePlayer tonePlayer, VoiceService voiceService) {
    return new AlarmViewModel(context, alarmDao, tonePlayer, voiceService);
  }
}
