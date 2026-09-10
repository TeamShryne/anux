package com.teamshryne.anux.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey val alias: String,
    val imageRef: String,
    val canonicalRef: String,
    val arch: String,
    val createdAt: Long,
)

@Dao
interface ContainerDao {
    @Query("SELECT * FROM containers ORDER BY alias")
    fun observe(): Flow<List<ContainerEntity>>

    @Query("SELECT * FROM containers ORDER BY alias")
    suspend fun all(): List<ContainerEntity>

    @Upsert
    suspend fun upsert(entity: ContainerEntity)

    @Query("DELETE FROM containers WHERE alias = :alias")
    suspend fun delete(alias: String)
}

@Database(entities = [ContainerEntity::class], version = 1, exportSchema = false)
abstract class AnuxDatabase : RoomDatabase() {
    abstract fun containers(): ContainerDao

    companion object {
        @Volatile
        private var instance: AnuxDatabase? = null

        fun get(context: Context): AnuxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnuxDatabase::class.java,
                    "anux.db",
                ).build().also { instance = it }
            }
    }
}
