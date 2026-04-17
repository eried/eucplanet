package com.eried.evendarkerbot.di;

import com.eried.evendarkerbot.data.db.AppDatabase;
import com.eried.evendarkerbot.data.db.TripDao;
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
public final class AppModule_ProvideTripDaoFactory implements Factory<TripDao> {
  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvideTripDaoFactory(Provider<AppDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public TripDao get() {
    return provideTripDao(dbProvider.get());
  }

  public static AppModule_ProvideTripDaoFactory create(Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvideTripDaoFactory(dbProvider);
  }

  public static TripDao provideTripDao(AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideTripDao(db));
  }
}
