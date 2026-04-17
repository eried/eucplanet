package com.eried.evendarkerbot.service;

import android.content.Context;
import com.eried.evendarkerbot.data.repository.SettingsRepository;
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
public final class VoiceService_Factory implements Factory<VoiceService> {
  private final Provider<Context> contextProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  public VoiceService_Factory(Provider<Context> contextProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  @Override
  public VoiceService get() {
    return newInstance(contextProvider.get(), settingsRepositoryProvider.get());
  }

  public static VoiceService_Factory create(Provider<Context> contextProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    return new VoiceService_Factory(contextProvider, settingsRepositoryProvider);
  }

  public static VoiceService newInstance(Context context, SettingsRepository settingsRepository) {
    return new VoiceService(context, settingsRepository);
  }
}
