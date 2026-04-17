package com.eried.evendarkerbot.service;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class TonePlayer_Factory implements Factory<TonePlayer> {
  @Override
  public TonePlayer get() {
    return newInstance();
  }

  public static TonePlayer_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static TonePlayer newInstance() {
    return new TonePlayer();
  }

  private static final class InstanceHolder {
    private static final TonePlayer_Factory INSTANCE = new TonePlayer_Factory();
  }
}
