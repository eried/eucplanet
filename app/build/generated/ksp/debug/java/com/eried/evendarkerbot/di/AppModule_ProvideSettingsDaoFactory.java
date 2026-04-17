package com.eried.evendarkerbot.di;

import com.eried.evendarkerbot.data.db.AppDatabase;
import com.eried.evendarkerbot.data.db.SettingsDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class AppModule_ProvideSettingsDaoFactory implements Factory<SettingsDao> {
  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvideSettingsDaoFactory(Provider<AppDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public SettingsDao get() {
    return provideSettingsDao(dbProvider.get());
  }

  public static AppModule_ProvideSettingsDaoFactory create(Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvideSettingsDaoFactory(dbProvider);
  }

  public static SettingsDao provideSettingsDao(AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideSettingsDao(db));
  }
}
