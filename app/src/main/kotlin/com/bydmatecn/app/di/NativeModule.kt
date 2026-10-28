package com.bydmatecn.app.di

import com.bydmatecn.app.data.nativestack.NativeParsReader
import com.bydmatecn.app.data.nativestack.ParsReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeModule {

    @Binds
    @Singleton
    abstract fun bindParsReader(impl: NativeParsReader): ParsReader
}
