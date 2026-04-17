package com.eried.evendarkerbot;

import com.eried.evendarkerbot.data.repository.SettingsRepository;
import com.eried.evendarkerbot.flic.FlicManager;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class MainActivity_MembersInjector implements MembersInjector<MainActivity> {
  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<FlicManager> flicManagerProvider;

  public MainActivity_MembersInjector(Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<FlicManager> flicManagerProvider) {
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.flicManagerProvider = flicManagerProvider;
  }

  public static MembersInjector<MainActivity> create(
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<FlicManager> flicManagerProvider) {
    return new MainActivity_MembersInjector(settingsRepositoryProvider, flicManagerProvider);
  }

  @Override
  public void injectMembers(MainActivity instance) {
    injectSettingsRepository(instance, settingsRepositoryProvider.get());
    injectFlicManager(instance, flicManagerProvider.get());
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.MainActivity.settingsRepository")
  public static void injectSettingsRepository(MainActivity instance,
      SettingsRepository settingsRepository) {
    instance.settingsRepository = settingsRepository;
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.MainActivity.flicManager")
  public static void injectFlicManager(MainActivity instance, FlicManager flicManager) {
    instance.flicManager = flicManager;
  }
}
