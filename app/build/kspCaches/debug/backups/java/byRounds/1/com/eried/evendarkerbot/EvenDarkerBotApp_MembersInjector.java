package com.eried.evendarkerbot;

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
public final class EvenDarkerBotApp_MembersInjector implements MembersInjector<EvenDarkerBotApp> {
  private final Provider<FlicManager> flicManagerProvider;

  public EvenDarkerBotApp_MembersInjector(Provider<FlicManager> flicManagerProvider) {
    this.flicManagerProvider = flicManagerProvider;
  }

  public static MembersInjector<EvenDarkerBotApp> create(
      Provider<FlicManager> flicManagerProvider) {
    return new EvenDarkerBotApp_MembersInjector(flicManagerProvider);
  }

  @Override
  public void injectMembers(EvenDarkerBotApp instance) {
    injectFlicManager(instance, flicManagerProvider.get());
  }

  @InjectedFieldSignature("com.eried.evendarkerbot.EvenDarkerBotApp.flicManager")
  public static void injectFlicManager(EvenDarkerBotApp instance, FlicManager flicManager) {
    instance.flicManager = flicManager;
  }
}
