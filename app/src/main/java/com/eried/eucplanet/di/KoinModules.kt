package com.eried.eucplanet.di

/** Every Koin module the app starts with, see EucPlanetApp.onCreate. */
val appModules = listOf(
    appModule,
    dataModule,
    serviceModule,
    bleModule,
    externalGpsModule,
    radarModule,
    tpmsModule,
    eucStatsModule,
    crewsModule,
    navModule,
    hudModule,
    companionModule,
    viewModelModule,
    workerModule,
)
