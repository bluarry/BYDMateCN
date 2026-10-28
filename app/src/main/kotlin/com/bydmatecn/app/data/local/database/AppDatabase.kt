package com.bydmatecn.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bydmatecn.app.data.local.dao.BatterySnapshotDao
import com.bydmatecn.app.data.local.dao.ChargeDao
import com.bydmatecn.app.data.local.dao.ChargePointDao
import com.bydmatecn.app.data.local.dao.CounterSnapshotDao
import com.bydmatecn.app.data.local.dao.IdleDrainDao
import com.bydmatecn.app.data.local.dao.OdometerSampleDao
import com.bydmatecn.app.data.local.dao.PlaceDao
import com.bydmatecn.app.data.local.dao.RuleDao
import com.bydmatecn.app.data.local.dao.RuleLogDao
import com.bydmatecn.app.data.local.dao.SettingsDao
import com.bydmatecn.app.data.local.dao.TariffPeriodDao
import com.bydmatecn.app.data.local.dao.TripDao
import com.bydmatecn.app.data.local.dao.LastStateDao
import com.bydmatecn.app.data.local.dao.TripPointDao
import com.bydmatecn.app.data.local.dao.TripTombstoneDao
import com.bydmatecn.app.data.local.dao.VehicleWriteLogDao
import com.bydmatecn.app.data.local.entity.BatterySnapshotEntity
import com.bydmatecn.app.data.local.entity.LastStateEntity
import com.bydmatecn.app.data.local.entity.ChargeEntity
import com.bydmatecn.app.data.local.entity.ChargePointEntity
import com.bydmatecn.app.data.local.entity.CounterSnapshotEntity
import com.bydmatecn.app.data.local.entity.IdleDrainEntity
import com.bydmatecn.app.data.local.entity.OdometerSampleEntity
import com.bydmatecn.app.data.local.entity.PlaceEntity
import com.bydmatecn.app.data.local.entity.RuleEntity
import com.bydmatecn.app.data.local.entity.RuleLogEntity
import com.bydmatecn.app.data.local.entity.SettingEntity
import com.bydmatecn.app.data.local.entity.TariffPeriodEntity
import com.bydmatecn.app.data.local.entity.TripEntity
import com.bydmatecn.app.data.local.entity.TripPointEntity
import com.bydmatecn.app.data.local.entity.TripTombstoneEntity
import com.bydmatecn.app.data.local.entity.VehicleWriteLogEntity

@Database(
    entities = [
        TripEntity::class,
        TripPointEntity::class,
        ChargeEntity::class,
        ChargePointEntity::class,
        SettingEntity::class,
        IdleDrainEntity::class,
        BatterySnapshotEntity::class,
        RuleEntity::class,
        RuleLogEntity::class,
        PlaceEntity::class,
        OdometerSampleEntity::class,
        LastStateEntity::class,
        VehicleWriteLogEntity::class,
        TripTombstoneEntity::class,
        TariffPeriodEntity::class,
        CounterSnapshotEntity::class
    ],
    version = 23,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        /** Current Room schema version. Must match the @Database(version = ...) annotation above. */
        const val SCHEMA_VERSION = 23
    }

    abstract fun tripDao(): TripDao
    abstract fun tripPointDao(): TripPointDao
    abstract fun chargeDao(): ChargeDao
    abstract fun chargePointDao(): ChargePointDao
    abstract fun settingsDao(): SettingsDao
    abstract fun idleDrainDao(): IdleDrainDao
    abstract fun batterySnapshotDao(): BatterySnapshotDao
    abstract fun ruleDao(): RuleDao
    abstract fun ruleLogDao(): RuleLogDao
    abstract fun placeDao(): PlaceDao
    abstract fun odometerSampleDao(): OdometerSampleDao
    abstract fun lastStateDao(): LastStateDao
    abstract fun vehicleWriteLogDao(): VehicleWriteLogDao
    abstract fun tripTombstoneDao(): TripTombstoneDao
    abstract fun tariffPeriodDao(): TariffPeriodDao
    abstract fun counterSnapshotDao(): CounterSnapshotDao
}
