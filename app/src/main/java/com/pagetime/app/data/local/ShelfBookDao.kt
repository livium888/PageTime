package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfBookDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllKeepingExisting(rows: List<ShelfBookEntity>)

    @Query("SELECT * FROM shelf_books WHERE shelfId = :shelfId ORDER BY position ASC")
    fun observeShelf(shelfId: String): Flow<List<ShelfBookEntity>>

    @Query("SELECT * FROM shelf_books WHERE shelfId = :shelfId ORDER BY position ASC")
    suspend fun shelf(shelfId: String): List<ShelfBookEntity>

    /**
     * Every shelf at once, for the bookshelf view.
     *
     * One query rather than a flow per shelf: the whole table is at most a few
     * hundred rows, and observing N shelves separately would mean the screen
     * had to know which shelves exist before it could ask about them.
     */
    @Query("SELECT * FROM shelf_books ORDER BY shelfId ASC, position ASC")
    fun observeAll(): Flow<List<ShelfBookEntity>>

    @Query("SELECT COUNT(*) FROM shelf_books WHERE shelfId = :shelfId")
    suspend fun countIn(shelfId: String): Int

    /**
     * Records what a catalogue said about one entry.
     *
     * Only the resolve columns are written, so a lookup can never clobber the
     * title, note or position that came from the shipped list.
     */
    @Query(
        "UPDATE shelf_books SET catalogSource = :source, catalogBookId = :catalogBookId, " +
            "availability = :availability, resolvedAt = :resolvedAt " +
            "WHERE shelfId = :shelfId AND slotId = :slotId"
    )
    suspend fun recordResolution(
        shelfId: String,
        slotId: String,
        source: String?,
        catalogBookId: String?,
        availability: String,
        resolvedAt: Long,
    )

    /**
     * Entries worth asking a catalogue about: never looked up, or looked up
     * and not found long enough ago that a catalogue may have gained it.
     */
    @Query(
        "SELECT * FROM shelf_books WHERE shelfId = :shelfId AND " +
            "(resolvedAt IS NULL OR (availability = 'unavailable' AND resolvedAt < :staleBefore)) " +
            "ORDER BY position ASC LIMIT :limit"
    )
    suspend fun needingResolution(
        shelfId: String,
        staleBefore: Long,
        limit: Int,
    ): List<ShelfBookEntity>

    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId AND slotId = :slotId")
    suspend fun remove(shelfId: String, slotId: String)
}
