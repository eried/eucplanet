package com.eried.evendarkerbot.ui.recording;

import android.content.Context;
import com.eried.evendarkerbot.data.repository.TripRepository;
import com.eried.evendarkerbot.data.repository.WheelRepository;
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
public final class RecordingViewModel_Factory implements Factory<RecordingViewModel> {
  private final Provider<TripRepository> tripRepositoryProvider;

  private final Provider<WheelRepository> wheelRepositoryProvider;

  private final Provider<Context> contextProvider;

  public RecordingViewModel_Factory(Provider<TripRepository> tripRepositoryProvider,
      Provider<WheelRepository> wheelRepositoryProvider, Provider<Context> contextProvider) {
    this.tripRepositoryProvider = tripRepositoryProvider;
    this.wheelRepositoryProvider = wheelRepositoryProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public RecordingViewModel get() {
    return newInstance(tripRepositoryProvider.get(), wheelRepositoryProvider.get(), contextProvider.get());
  }

  public static RecordingViewModel_Factory create(Provider<TripRepository> tripRepositoryProvider,
      Provider<WheelRepository> wheelRepositoryProvider, Provider<Context> contextProvider) {
    return new RecordingViewModel_Factory(tripRepositoryProvider, wheelRepositoryProvider, contextProvider);
  }

  public static RecordingViewModel newInstance(TripRepository tripRepository,
      WheelRepository wheelRepository, Context context) {
    return new RecordingViewModel(tripRepository, wheelRepository, context);
  }
}
