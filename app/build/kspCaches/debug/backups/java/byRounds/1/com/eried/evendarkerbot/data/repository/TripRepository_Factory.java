package com.eried.evendarkerbot.data.repository;

import android.content.Context;
import com.eried.evendarkerbot.data.db.TripDao;
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
public final class TripRepository_Factory implements Factory<TripRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<TripDao> tripDaoProvider;

  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  public TripRepository_Factory(Provider<Context> contextProvider,
      Provider<TripDao> tripDaoProvider, Provider<WheelRepository> wheelRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.tripDaoProvider = tripDaoProvider;
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.voiceServiceProvider = voiceServiceProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  @Override
  public TripRepository get() {
    return newInstance(contextProvider.get(), tripDaoProvider.get(), wheelRepositoryProvider.get(), voiceServiceProvider.get(), settingsRepositoryProvider.get());
  }

  public static TripRepository_Factory create(Provider<Context> contextProvider,
      Provider<TripDao> tripDaoProvider, Provider<WheelRepository> wheelRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    return new TripRepository_Factory(contextProvider, tripDaoProvider, wheelRepositoryProvider, voiceServiceProvider, settingsRepositoryProvider);
  }

  public static TripRepository newInstance(Context context, TripDao tripDao,
      WheelRepository wheelRepository, VoiceService voiceService,
      SettingsRepository settingsRepository) {
    return new TripRepository(context, tripDao, wheelRepository, voiceService, settingsRepository);
  }
}
