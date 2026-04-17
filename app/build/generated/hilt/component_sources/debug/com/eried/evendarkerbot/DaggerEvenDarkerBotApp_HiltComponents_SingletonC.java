package com.eried.evendarkerbot;

import android.app.Activity;
import android.app.Service;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.eried.evendarkerbot.ble.BleConnectionManager;
import com.eried.evendarkerbot.ble.BleScanner;
import com.eried.evendarkerbot.data.db.AlarmDao;
import com.eried.evendarkerbot.data.db.AppDatabase;
import com.eried.evendarkerbot.data.db.SettingsDao;
import com.eried.evendarkerbot.data.db.TripDao;
import com.eried.evendarkerbot.data.repository.SettingsRepository;
import com.eried.evendarkerbot.data.repository.TripRepository;
import com.eried.evendarkerbot.data.repository.WheelRepository;
import com.eried.evendarkerbot.di.AppModule_ProvideAlarmDaoFactory;
import com.eried.evendarkerbot.di.AppModule_ProvideDatabaseFactory;
import com.eried.evendarkerbot.di.AppModule_ProvideSettingsDaoFactory;
import com.eried.evendarkerbot.di.AppModule_ProvideTripDaoFactory;
import com.eried.evendarkerbot.flic.FlicManager;
import com.eried.evendarkerbot.service.AlarmEngine;
import com.eried.evendarkerbot.service.AutomationManager;
import com.eried.evendarkerbot.service.TonePlayer;
import com.eried.evendarkerbot.service.VoiceService;
import com.eried.evendarkerbot.service.WheelService;
import com.eried.evendarkerbot.service.WheelService_MembersInjector;
import com.eried.evendarkerbot.ui.dashboard.DashboardViewModel;
import com.eried.evendarkerbot.ui.dashboard.DashboardViewModel_HiltModules;
import com.eried.evendarkerbot.ui.dashboard.DashboardViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.dashboard.DashboardViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.eried.evendarkerbot.ui.dashboard.MetricDetailViewModel;
import com.eried.evendarkerbot.ui.dashboard.MetricDetailViewModel_HiltModules;
import com.eried.evendarkerbot.ui.dashboard.MetricDetailViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.dashboard.MetricDetailViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.eried.evendarkerbot.ui.recording.RecordingViewModel;
import com.eried.evendarkerbot.ui.recording.RecordingViewModel_HiltModules;
import com.eried.evendarkerbot.ui.recording.RecordingViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.recording.RecordingViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.eried.evendarkerbot.ui.scan.ScanViewModel;
import com.eried.evendarkerbot.ui.scan.ScanViewModel_HiltModules;
import com.eried.evendarkerbot.ui.scan.ScanViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.scan.ScanViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.eried.evendarkerbot.ui.settings.AlarmViewModel;
import com.eried.evendarkerbot.ui.settings.AlarmViewModel_HiltModules;
import com.eried.evendarkerbot.ui.settings.AlarmViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.settings.AlarmViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.eried.evendarkerbot.ui.settings.FlicViewModel;
import com.eried.evendarkerbot.ui.settings.FlicViewModel_HiltModules;
import com.eried.evendarkerbot.ui.settings.FlicViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.settings.FlicViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.eried.evendarkerbot.ui.settings.SettingsViewModel;
import com.eried.evendarkerbot.ui.settings.SettingsViewModel_HiltModules;
import com.eried.evendarkerbot.ui.settings.SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.eried.evendarkerbot.ui.settings.SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.MapBuilder;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

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
public final class DaggerEvenDarkerBotApp_HiltComponents_SingletonC {
  private DaggerEvenDarkerBotApp_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public EvenDarkerBotApp_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements EvenDarkerBotApp_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements EvenDarkerBotApp_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements EvenDarkerBotApp_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements EvenDarkerBotApp_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements EvenDarkerBotApp_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements EvenDarkerBotApp_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements EvenDarkerBotApp_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public EvenDarkerBotApp_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends EvenDarkerBotApp_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    private ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends EvenDarkerBotApp_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    private FragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }
  }

  private static final class ViewCImpl extends EvenDarkerBotApp_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    private ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends EvenDarkerBotApp_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    private ActivityCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    @Override
    public void injectMainActivity(MainActivity mainActivity) {
      injectMainActivity2(mainActivity);
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(MapBuilder.<String, Boolean>newMapBuilder(7).put(AlarmViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, AlarmViewModel_HiltModules.KeyModule.provide()).put(DashboardViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, DashboardViewModel_HiltModules.KeyModule.provide()).put(FlicViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, FlicViewModel_HiltModules.KeyModule.provide()).put(MetricDetailViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, MetricDetailViewModel_HiltModules.KeyModule.provide()).put(RecordingViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, RecordingViewModel_HiltModules.KeyModule.provide()).put(ScanViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, ScanViewModel_HiltModules.KeyModule.provide()).put(SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, SettingsViewModel_HiltModules.KeyModule.provide()).build());
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    private MainActivity injectMainActivity2(MainActivity instance) {
      MainActivity_MembersInjector.injectSettingsRepository(instance, singletonCImpl.settingsRepositoryProvider.get());
      MainActivity_MembersInjector.injectFlicManager(instance, singletonCImpl.flicManagerProvider.get());
      return instance;
    }
  }

  private static final class ViewModelCImpl extends EvenDarkerBotApp_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    private Provider<AlarmViewModel> alarmViewModelProvider;

    private Provider<DashboardViewModel> dashboardViewModelProvider;

    private Provider<FlicViewModel> flicViewModelProvider;

    private Provider<MetricDetailViewModel> metricDetailViewModelProvider;

    private Provider<RecordingViewModel> recordingViewModelProvider;

    private Provider<ScanViewModel> scanViewModelProvider;

    private Provider<SettingsViewModel> settingsViewModelProvider;

    private ViewModelCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, SavedStateHandle savedStateHandleParam,
        ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;

      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.alarmViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.dashboardViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.flicViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.metricDetailViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
      this.recordingViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 4);
      this.scanViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 5);
      this.settingsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 6);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(MapBuilder.<String, javax.inject.Provider<ViewModel>>newMapBuilder(7).put(AlarmViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) alarmViewModelProvider)).put(DashboardViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) dashboardViewModelProvider)).put(FlicViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) flicViewModelProvider)).put(MetricDetailViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) metricDetailViewModelProvider)).put(RecordingViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) recordingViewModelProvider)).put(ScanViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) scanViewModelProvider)).put(SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) settingsViewModelProvider)).build());
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return Collections.<Class<?>, Object>emptyMap();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.eried.evendarkerbot.ui.settings.AlarmViewModel 
          return (T) new AlarmViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.alarmDao(), singletonCImpl.tonePlayerProvider.get(), singletonCImpl.voiceServiceProvider.get());

          case 1: // com.eried.evendarkerbot.ui.dashboard.DashboardViewModel 
          return (T) new DashboardViewModel(singletonCImpl.wheelRepositoryProvider.get(), singletonCImpl.settingsRepositoryProvider.get(), singletonCImpl.tripRepositoryProvider.get(), singletonCImpl.voiceServiceProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 2: // com.eried.evendarkerbot.ui.settings.FlicViewModel 
          return (T) new FlicViewModel(singletonCImpl.settingsRepositoryProvider.get(), singletonCImpl.flicManagerProvider.get());

          case 3: // com.eried.evendarkerbot.ui.dashboard.MetricDetailViewModel 
          return (T) new MetricDetailViewModel(singletonCImpl.wheelRepositoryProvider.get(), singletonCImpl.settingsRepositoryProvider.get());

          case 4: // com.eried.evendarkerbot.ui.recording.RecordingViewModel 
          return (T) new RecordingViewModel(singletonCImpl.tripRepositoryProvider.get(), singletonCImpl.wheelRepositoryProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 5: // com.eried.evendarkerbot.ui.scan.ScanViewModel 
          return (T) new ScanViewModel(singletonCImpl.bleScannerProvider.get(), singletonCImpl.settingsRepositoryProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 6: // com.eried.evendarkerbot.ui.settings.SettingsViewModel 
          return (T) new SettingsViewModel(singletonCImpl.settingsRepositoryProvider.get(), singletonCImpl.wheelRepositoryProvider.get(), singletonCImpl.voiceServiceProvider.get(), singletonCImpl.tripRepositoryProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends EvenDarkerBotApp_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    private Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    private ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle 
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends EvenDarkerBotApp_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    private ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }

    @Override
    public void injectWheelService(WheelService wheelService) {
      injectWheelService2(wheelService);
    }

    private WheelService injectWheelService2(WheelService instance) {
      WheelService_MembersInjector.injectWheelRepository(instance, singletonCImpl.wheelRepositoryProvider.get());
      WheelService_MembersInjector.injectSettingsRepository(instance, singletonCImpl.settingsRepositoryProvider.get());
      WheelService_MembersInjector.injectVoiceService(instance, singletonCImpl.voiceServiceProvider.get());
      WheelService_MembersInjector.injectTripRepository(instance, singletonCImpl.tripRepositoryProvider.get());
      WheelService_MembersInjector.injectAutomationManager(instance, singletonCImpl.automationManagerProvider.get());
      return instance;
    }
  }

  private static final class SingletonCImpl extends EvenDarkerBotApp_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    private Provider<AppDatabase> provideDatabaseProvider;

    private Provider<SettingsRepository> settingsRepositoryProvider;

    private Provider<BleConnectionManager> bleConnectionManagerProvider;

    private Provider<TonePlayer> tonePlayerProvider;

    private Provider<VoiceService> voiceServiceProvider;

    private Provider<AlarmEngine> alarmEngineProvider;

    private Provider<WheelRepository> wheelRepositoryProvider;

    private Provider<TripRepository> tripRepositoryProvider;

    private Provider<FlicManager> flicManagerProvider;

    private Provider<BleScanner> bleScannerProvider;

    private Provider<AutomationManager> automationManagerProvider;

    private SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);

    }

    private SettingsDao settingsDao() {
      return AppModule_ProvideSettingsDaoFactory.provideSettingsDao(provideDatabaseProvider.get());
    }

    private AlarmDao alarmDao() {
      return AppModule_ProvideAlarmDaoFactory.provideAlarmDao(provideDatabaseProvider.get());
    }

    private TripDao tripDao() {
      return AppModule_ProvideTripDaoFactory.provideTripDao(provideDatabaseProvider.get());
    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.provideDatabaseProvider = DoubleCheck.provider(new SwitchingProvider<AppDatabase>(singletonCImpl, 2));
      this.settingsRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<SettingsRepository>(singletonCImpl, 1));
      this.bleConnectionManagerProvider = DoubleCheck.provider(new SwitchingProvider<BleConnectionManager>(singletonCImpl, 4));
      this.tonePlayerProvider = DoubleCheck.provider(new SwitchingProvider<TonePlayer>(singletonCImpl, 6));
      this.voiceServiceProvider = DoubleCheck.provider(new SwitchingProvider<VoiceService>(singletonCImpl, 7));
      this.alarmEngineProvider = DoubleCheck.provider(new SwitchingProvider<AlarmEngine>(singletonCImpl, 5));
      this.wheelRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<WheelRepository>(singletonCImpl, 3));
      this.tripRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<TripRepository>(singletonCImpl, 8));
      this.flicManagerProvider = DoubleCheck.provider(new SwitchingProvider<FlicManager>(singletonCImpl, 0));
      this.bleScannerProvider = DoubleCheck.provider(new SwitchingProvider<BleScanner>(singletonCImpl, 9));
      this.automationManagerProvider = DoubleCheck.provider(new SwitchingProvider<AutomationManager>(singletonCImpl, 10));
    }

    @Override
    public void injectEvenDarkerBotApp(EvenDarkerBotApp evenDarkerBotApp) {
      injectEvenDarkerBotApp2(evenDarkerBotApp);
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return Collections.<Boolean>emptySet();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    private EvenDarkerBotApp injectEvenDarkerBotApp2(EvenDarkerBotApp instance) {
      EvenDarkerBotApp_MembersInjector.injectFlicManager(instance, flicManagerProvider.get());
      return instance;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.eried.evendarkerbot.flic.FlicManager 
          return (T) new FlicManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.settingsRepositoryProvider.get(), singletonCImpl.wheelRepositoryProvider.get(), singletonCImpl.tripRepositoryProvider.get(), singletonCImpl.voiceServiceProvider.get());

          case 1: // com.eried.evendarkerbot.data.repository.SettingsRepository 
          return (T) new SettingsRepository(singletonCImpl.settingsDao());

          case 2: // com.eried.evendarkerbot.data.db.AppDatabase 
          return (T) AppModule_ProvideDatabaseFactory.provideDatabase(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 3: // com.eried.evendarkerbot.data.repository.WheelRepository 
          return (T) new WheelRepository(singletonCImpl.bleConnectionManagerProvider.get(), singletonCImpl.settingsRepositoryProvider.get(), singletonCImpl.alarmEngineProvider.get(), singletonCImpl.voiceServiceProvider.get());

          case 4: // com.eried.evendarkerbot.ble.BleConnectionManager 
          return (T) new BleConnectionManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 5: // com.eried.evendarkerbot.service.AlarmEngine 
          return (T) new AlarmEngine(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.alarmDao(), singletonCImpl.tonePlayerProvider.get(), singletonCImpl.voiceServiceProvider.get());

          case 6: // com.eried.evendarkerbot.service.TonePlayer 
          return (T) new TonePlayer();

          case 7: // com.eried.evendarkerbot.service.VoiceService 
          return (T) new VoiceService(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.settingsRepositoryProvider.get());

          case 8: // com.eried.evendarkerbot.data.repository.TripRepository 
          return (T) new TripRepository(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.tripDao(), singletonCImpl.wheelRepositoryProvider.get(), singletonCImpl.voiceServiceProvider.get(), singletonCImpl.settingsRepositoryProvider.get());

          case 9: // com.eried.evendarkerbot.ble.BleScanner 
          return (T) new BleScanner(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 10: // com.eried.evendarkerbot.service.AutomationManager 
          return (T) new AutomationManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.wheelRepositoryProvider.get(), singletonCImpl.tripRepositoryProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
