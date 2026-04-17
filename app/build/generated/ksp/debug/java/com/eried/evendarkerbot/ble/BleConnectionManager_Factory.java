package com.eried.evendarkerbot.ble;

import android.content.Context;
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
public final class BleConnectionManager_Factory implements Factory<BleConnectionManager> {
  private final Provider<Context> contextProvider;

  public BleConnectionManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public BleConnectionManager get() {
    return newInstance(contextProvider.get());
  }

  public static BleConnectionManager_Factory create(Provider<Context> contextProvider) {
    return new BleConnectionManager_Factory(contextProvider);
  }

  public static BleConnectionManager newInstance(Context context) {
    return new BleConnectionManager(context);
  }
}
