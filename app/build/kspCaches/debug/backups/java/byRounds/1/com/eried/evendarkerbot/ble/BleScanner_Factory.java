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
public final class BleScanner_Factory implements Factory<BleScanner> {
  private final Provider<Context> contextProvider;

  public BleScanner_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public BleScanner get() {
    return newInstance(contextProvider.get());
  }

  public static BleScanner_Factory create(Provider<Context> contextProvider) {
    return new BleScanner_Factory(contextProvider);
  }

  public static BleScanner newInstance(Context context) {
    return new BleScanner(context);
  }
}
