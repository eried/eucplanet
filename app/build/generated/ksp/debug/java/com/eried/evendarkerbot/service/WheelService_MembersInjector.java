package com.eried.evendarkerbot.service;

import com.eried.evendarkerbot.data.repository.SettingsRepository;
import com.eried.evendarkerbot.data.repository.TripRepository;
import com.eried.evendarkerbot.data.repository.WheelRepository;
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
public final class WheelService_MembersInjector implements MembersInjector<WheelService> {
  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<VoiceService> voiceServiceProvider;

  private final Provider<TripRepository> tripRepositoryProvider;

  private final Provider<AutomationManager> automationManagerProvider;

  public WheelService_MembersInjector(Provider<WheelRepository> wheelRepositoryProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider, Provider<TripRepository> tripRepositoryProvider,
      Provider<AutomationManager> automationManagerProvider) {
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.voiceServiceProvider = voiceServiceProvider;
    this.tripRepositoryProvider = tripRepositoryProvider;
    this.automationManagerProvider = automationManagerProvider;
  }

  public static MembersInjector<WheelService> create(
      Provider<WheelRepository> wheelRepositoryProvider,
      Provider<SettingsRepository> settingsRepositoryProvider,
      Provider<VoiceService> voiceServiceProvider, Provider<TripRepository> tripRepositoryProvider,
      Provider<AutomationManager> automationManagerProvider) {
    return new WheelService_MembersInjector(wheelRepositoryProvider, settingsRepositoryProvider, voiceServiceProvider, tripRepositoryProvider, automationManagerProvider);
  }

  @Override
  public void injectMembers(WheelService instance) {
    injectWheelRepository(instance, wheelRepositoryProvider.get());
    injectSettingsRepository(instance, settingsRepositoryProvider.get());
    injectVoiceService(instance, voiceServiceProvider.get());
    injectTripRepository(instance, tripRepositoryProvider.get());
    injectAutomationManager(instance, automationManagerProvider.get());
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.service.WheelService.wheelRepository")
  public static void injectWheelRepository(WheelService instance, WheelRepository wheelRepository) {
    instance.wheelRepository = wheelRepository;
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.service.WheelService.settingsRepository")
  public static void injectSettingsRepository(WheelService instance,
      SettingsRepository settingsRepository) {
    instance.settingsRepository = settingsRepository;
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.service.WheelService.voiceService")
  public static void injectVoiceService(WheelService instance, VoiceService voiceService) {
    instance.voiceService = voiceService;
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.service.WheelService.tripRepository")
  public static void injectTripRepository(WheelService instance, TripRepository tripRepository) {
    instance.tripRepository = tripRepository;
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.service.WheelService.automationManager")
  public static void injectAutomationManager(WheelService instance,
      AutomationManager automationManager) {
    instance.automationManager = automationManager;
  }
}
