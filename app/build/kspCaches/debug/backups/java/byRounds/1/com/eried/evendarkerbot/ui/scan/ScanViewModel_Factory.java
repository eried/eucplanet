package com.eried.evendarkerbot.ui.scan;

import android.content.Context;
import com.eried.evendarkerbot.ble.BleScanner;
import com.eried.evendarkerbot.data.repository.SettingsRepository;
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
public final class ScanViewModel_Factory implements Factory<ScanViewModel> {
  private final Provider<BleScanner> bleScannerProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  private final Provider<Context> contextProvider;

  public ScanViewModel_Factory(Provider<BleScanner> bleScannerProvider,
      Provider<SettingsRepository> settingsRepositoryProvider, Provider<Context> contextProvider) {
    this.bleScannerProvider = bleScannerProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public ScanViewModel get() {
    return newInstance(bleScannerProvider.get(), settingsRepositoryProvider.get(), contextProvider.get());
  }

  public static ScanViewModel_Factory create(Provider<BleScanner> bleScannerProvider,
      Provider<SettingsRepository> settingsRepositoryProvider, Provider<Context> contextProvider) {
    return new ScanViewModel_Factory(bleScannerProvider, settingsRepositoryProvider, contextProvider);
  }

  public static ScanViewModel newInstance(BleScanner bleScanner,
      SettingsRepository settingsRepository, Context context) {
    return new ScanViewModel(bleScanner, settingsRepository, context);
  }
}
