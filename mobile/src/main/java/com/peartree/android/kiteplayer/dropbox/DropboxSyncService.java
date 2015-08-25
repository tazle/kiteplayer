package com.peartree.android.kiteplayer.dropbox;

import android.app.Application;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.support.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.exception.DropboxException;
import com.peartree.android.kiteplayer.R;
import com.peartree.android.kiteplayer.database.DropboxDBEntry;
import com.peartree.android.kiteplayer.database.DropboxDBEntryDAO;
import com.peartree.android.kiteplayer.database.DropboxDBSong;
import com.peartree.android.kiteplayer.database.DropboxDBSongDAO;
import com.peartree.android.kiteplayer.model.AlbumArtLoader;
import com.peartree.android.kiteplayer.model.MusicProvider;
import com.peartree.android.kiteplayer.utils.ImmutableFileLRUCache;
import com.peartree.android.kiteplayer.utils.LogHelper;
import com.peartree.android.kiteplayer.utils.NetworkHelper;
import com.peartree.android.kiteplayer.utils.PrefUtils;
import com.peartree.android.kiteplayer.utils.SongCacheHelper;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import rx.Observable;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

import static com.peartree.android.kiteplayer.model.MusicProvider.FLAG_SONG_METADATA_IMAGE;
import static com.peartree.android.kiteplayer.model.MusicProvider.FLAG_SONG_METADATA_TEXT;
import static com.peartree.android.kiteplayer.model.MusicProvider.FLAG_SONG_PLAYABLE;
import static com.peartree.android.kiteplayer.utils.SongCacheHelper.LARGE_ALBUM_ART_DIMENSIONS;

@Singleton
public class DropboxSyncService {

    private static final String TAG = LogHelper.makeLogTag(DropboxSyncService.class);

    private Context mApplicationContext;

    private DropboxAPI<AndroidAuthSession> mDropboxApi;

    private DropboxDBEntryDAO mEntryDao;
    private DropboxDBSongDAO mSongDao;
    private ImmutableFileLRUCache mCachedSongs;

    private final PublishSubject<AsyncCacheRequest> mMetadataSyncQueue;

    @Inject
    public DropboxSyncService(Application application,
                              DropboxAPI<AndroidAuthSession> dbApi,
                              DropboxDBEntryDAO entryDao,
                              DropboxDBSongDAO songDao,
                              ImmutableFileLRUCache cachedSongs) {

        this.mApplicationContext = application.getApplicationContext();
        this.mDropboxApi = dbApi;
        this.mEntryDao = entryDao;
        this.mSongDao = songDao;
        this.mCachedSongs = cachedSongs;

        this.mMetadataSyncQueue = PublishSubject.create(); // No need to serialize

        // Subject works as an event bus through which entries for which metadata is missing
        // are processed asynchronously
        this.mMetadataSyncQueue
                .distinct(request -> request)
                .window(1)
                .onBackpressureBuffer()
                .observeOn(Schedulers.io())
                .subscribe(this::synchronizeSongDB);
    }

    public Observable<Long> synchronizeEntryDB() {

        LogHelper.d(TAG, "Dropbox auth status: " + mDropboxApi.getSession().isLinked());

        return Observable.create(subscriber -> {

                    LogHelper.d(TAG, "synchronizeEntryDB - Subscribing on thread: " + Thread.currentThread().getName());

                    DropboxAPI.DeltaPage<DropboxAPI.Entry> deltaPage;
                    DropboxAPI.Entry dbEntry;
                    DropboxDBEntry entry;
                    String deltaCursor;

                    int pageCounter = 1;

                    deltaCursor = PrefUtils.getDropboxDeltaCursor(mApplicationContext);

                    try {

                        do {

                            deltaPage = mDropboxApi.delta(deltaCursor);

                            LogHelper.d(TAG, "synchronizeEntryDB - Processing delta page #" + (pageCounter++) + " size: " + deltaPage.entries.size());

                            for (DropboxAPI.DeltaEntry<DropboxAPI.Entry> deltaEntry : deltaPage.entries) {

                                dbEntry = deltaEntry.metadata;

                                if (dbEntry == null || dbEntry.isDeleted) {
                                    mEntryDao.deleteTreeByAncestorDir(deltaEntry.lcPath);
                                    LogHelper.d(TAG, "synchronizeEntryDB - Deleted entry for: " + deltaEntry.lcPath);

                                    continue;
                                }

                                entry = new DropboxDBEntry();

                                entry.setIsDir(dbEntry.isDir);
                                entry.setRoot(dbEntry.root);
                                entry.setParentDir(dbEntry.parentPath());
                                entry.setFilename(dbEntry.fileName());

                                entry.setRev(dbEntry.rev);
                                entry.setHash(dbEntry.hash);
                                entry.setModified(dbEntry.modified);
                                entry.setClientMtime(dbEntry.clientMtime);

                                entry.setMimeType(dbEntry.mimeType);
                                entry.setIcon(dbEntry.icon);
                                entry.setThumbExists(dbEntry.thumbExists);

                                long id = mEntryDao.insertOrReplace(entry);

                                LogHelper.d(TAG, "synchronizeEntryDB - Saved entry for: " + dbEntry.parentPath() + " with id: " + id);

                                subscriber.onNext(id);
                            }

                            deltaCursor = deltaPage.cursor;
                            PrefUtils.setDropboxDeltaCursor(mApplicationContext, deltaPage.cursor);

                        } while (deltaPage.hasMore);

                        subscriber.onCompleted();
                        LogHelper.d(TAG, "synchronizeEntryDB - Finished successfully");

                    } catch (DropboxException dbe) {

                        subscriber.onError(dbe);
                        LogHelper.e(TAG, "synchronizeEntryDB - Finished with error", dbe);

                    }

                }
        );
    }

    public Observable<DropboxDBEntry> fillSongMetadata(Observable<DropboxDBEntry> entries, int cacheFlags) {
        return entries.map(entry -> {

            LogHelper.d(TAG, "fillSongMetadata - Started for: " + entry.getFullPath() + ". Observing on thread: " + Thread.currentThread().getName());

            if (entry.isDir()) {
                LogHelper.d(TAG, "fillSongMetadata - Found directory. Nothing to do.");
                return entry;
            }

            DropboxDBSong song = mSongDao.findByEntryId(entry.getId());

            if (song == null) {
                song = new DropboxDBSong();
                song.setEntryId(entry.getId());
            }

            entry.setSong(song);

            if (!song.hasLatestMetadata() && (cacheFlags & (FLAG_SONG_METADATA_TEXT | FLAG_SONG_METADATA_IMAGE)) > 0) {
                LogHelper.d(TAG, "fillSongMetadata - Metadata update requested for: " + entry.getFullPath() + ". Queueing request for synchronization.");
                mMetadataSyncQueue.onNext(new AsyncCacheRequest(entry, cacheFlags));
            }

            if ((cacheFlags & FLAG_SONG_PLAYABLE) == FLAG_SONG_PLAYABLE) {
                try {
                    if (getCachedSongFile(entry) == null) {
                        refreshDownloadURL(entry);
                    }
                } catch (MalformedURLException | DropboxException e) {
                    LogHelper.d(TAG, "fillSongMetadata - Unable to refresh download URL for: " + entry.getFullPath(), e);
                }
            }

            return entry;

        });
    }

    private void synchronizeSongDB(Observable<AsyncCacheRequest> cacheRequestObservable) {

        cacheRequestObservable
                .subscribeOn(Schedulers.newThread())
                .subscribe(request -> synchronizeSongDB(request.getEntry(), request.getCacheFlags()));

    }

    private void synchronizeSongDB(DropboxDBEntry entry, int cacheFlag) {

        LogHelper.d(TAG, "synchronizeSongDB - Started for: " + entry.getFullPath() + ". Observing on thread: " + Thread.currentThread().getName());

        DropboxDBSong song;
        File cachedSongFile;

        boolean updateSongInDB = false;

        song = entry.getSong();

        if (song == null) {
            LogHelper.d(TAG, "synchronizeSongDB - Song is null for entry: " + entry.getFullPath() + ". Ignoring...");
            return;
        }

        if (song.getEntryId() != entry.getId()) {
            LogHelper.d(TAG, "synchronizeSongDB - Song has mismatching ID for entry: " + entry.getFullPath() + ". Ignoring...");
            return;
        }

        cachedSongFile = getCachedSongFile(entry, -1, cacheFlag);
        if (cachedSongFile != null) {

            LogHelper.d(TAG, "synchronizeSongDB - Song not cached for entry: " + entry.getFullPath());

            if (cachedSongFile == null && (cacheFlag & FLAG_SONG_PLAYABLE) == FLAG_SONG_PLAYABLE) {
                LogHelper.d(TAG, "synchronizeSongDB - Attempting to refresh URL for entry: " + entry.getFullPath());

                try {
                    refreshDownloadURL(entry);
                } catch (MalformedURLException | DropboxException e) {
                    song.setDownloadURL(null);
                    song.setDownloadURLExpiration(null);
                    LogHelper.w(TAG, "synchronizeSongDB - Unable to refresh download URL for: " + entry.getFullPath(), e);
                }
                updateSongInDB = true;

            }
        }

        MediaMetadataRetriever retriever = null;

        // If song metadata is current, skip
        // Unless image is required and song is readily available in cache
        if (!song.hasLatestMetadata() ||
                ((cacheFlag & FLAG_SONG_METADATA_IMAGE) == FLAG_SONG_METADATA_IMAGE && cachedSongFile != null)) {

            LogHelper.d(TAG, "synchronizeSongDB - Metadata retriever required for entry: " + entry.getFullPath());
            retriever = initializeMediaMetadataRetriever(entry, cachedSongFile);
        }

        if (retriever != null &&
                !song.hasLatestMetadata() &&
                (cacheFlag & FLAG_SONG_METADATA_TEXT) == FLAG_SONG_METADATA_TEXT) {

            LogHelper.d(TAG, "synchronizeSongDB - Updating text metadata for entry: " + entry.getFullPath());

            song.setAlbum(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            song.setArtist(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            song.setGenre(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            song.setTitle(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));

            String tmpString;

            if ((tmpString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) != null) {
                try {
                    song.setDuration(Long.valueOf(tmpString));
                } catch (NumberFormatException e) {
                    LogHelper.w(TAG, "synchronizeSongDB - Invalid duration: " + tmpString + " for file: " + entry.getFullPath(), e);
                }
            }

            if ((tmpString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)) != null) {
                try {
                    song.setTrackNumber(Integer.valueOf(tmpString));
                } catch (NumberFormatException e) {
                    LogHelper.w(TAG, "synchronizeSongDB - Invalid track number: " + tmpString + " for file: " + entry.getFullPath(), e);
                }
            }

            if ((tmpString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)) != null) {
                try {
                    song.setTotalTracks(Integer.valueOf(tmpString));
                } catch (NumberFormatException e) {
                    LogHelper.w(TAG, "synchronizeSongDB - Invalid number of tracks: " + tmpString + " for file: " + entry.getFullPath(), e);
                }
            }

            song.setHasLatestMetadata(true);
            updateSongInDB = true;

        }

        if (retriever != null) {

            LogHelper.d(TAG, "synchronizeSongDB - Updating image data for entry: " + entry.getFullPath());

            // Cache album art
            try {

                byte[] embeddedPicture = retriever.getEmbeddedPicture();

                if (embeddedPicture != null && embeddedPicture.length > 0) {
                    Glide
                            .with(mApplicationContext)
                            .load(retriever.getEmbeddedPicture())
                            .fallback(R.drawable.ic_default_art)
                            .signature(new AlbumArtLoader.Key(MusicProvider.buildMetadataFromDBEntry(entry, cachedSongFile)))
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(LARGE_ALBUM_ART_DIMENSIONS[0], LARGE_ALBUM_ART_DIMENSIONS[1])
                            .get();

                    LogHelper.d(TAG, "synchronizeSongDB - Cached album art image for: " + entry.getFullPath());

                } else {
                    song.setHasValidAlbumArt(false);
                }

            } catch (InterruptedException | ExecutionException e) {
                song.setHasValidAlbumArt(false);
                LogHelper.w(TAG, "synchronizeSongDB - Failed to cache album art image for: " + entry.getFullPath(), e);
            }
        }

        if (updateSongInDB) {

            // TODO Understand why metadata is never saved.
            long id = mSongDao.insertOrReplace(song);
            LogHelper.d(TAG, "synchronizeSongDB - Updated song for: " + entry.getFullPath() + " with id: " + id);
        }

    }

    private void refreshDownloadURL(DropboxDBEntry entry) throws MalformedURLException, DropboxException {

        DropboxDBSong song = entry.getSong();
        DropboxAPI.DropboxLink link;

        boolean hasValidDownloadURL =
                !(song.getDownloadURL() == null ||
                        song.getDownloadURLExpiration() == null ||
                        song.getDownloadURLExpiration().compareTo(new Date()) <= 0);

        if (!hasValidDownloadURL) {

            link = mDropboxApi.media(entry.getFullPath(), false);

            LogHelper.d(TAG, "refreshDownloadURL - Generated new download URL for: " + entry.getFullPath());

            song.setDownloadURL(new URL(link.url));
            song.setDownloadURLExpiration(link.expires);

        }

    }

    private
    @Nullable
    File downloadSongDataIntoCache(DropboxDBEntry entry) {

        LogHelper.d(TAG, "downloadSongDataIntoCache - Starting download of:" + entry.getFullPath());

        File newCacheFile = mCachedSongs.newFile(
                SongCacheHelper.makeLRUCacheFileName(entry),
                cacheStream -> mDropboxApi.getFile(entry.getFullPath(), entry.getRev(), cacheStream, null));

        if (newCacheFile != null) {
            LogHelper.d(TAG, "downloadSongDataIntoCache - Finished download of:" + entry.getFullPath());
        } else {
            LogHelper.w(TAG, "downloadSongDataIntoCache - Failed download of:" + entry.getFullPath());
        }

        return newCacheFile;
    }

    private
    @Nullable
    MediaMetadataRetriever initializeMediaMetadataRetriever(DropboxDBEntry entry, @Nullable File cachedSongFile) {

        DropboxDBSong song = entry.getSong();

        MediaMetadataRetriever retriever;
        retriever = new MediaMetadataRetriever();

        try {
            if (cachedSongFile != null) {
                LogHelper.d(TAG, "initializeMediaMetadataRetriever - Initializing metadata retriever initialized for: " + entry.getFullPath() + " with file: " + cachedSongFile.getPath());
                retriever.setDataSource(cachedSongFile.getPath());
            } else if (song != null && song.getDownloadURL() != null) {
                LogHelper.d(TAG, "initializeMediaMetadataRetriever - Initializing metadata retriever initialized for: " + entry.getFullPath() + " with URL: " + song.getDownloadURL());
                retriever.setDataSource(song.getDownloadURL().toString(), new HashMap<String, String>());
            } else {
                return null;
            }
        } catch (RuntimeException e) {
            LogHelper.w(TAG, "initializeMediaMetadataRetriever - Unable to initialize metadata retriever for: " + entry.getFullPath(), e);
            return null;
        }

        LogHelper.d(TAG, "initializeMediaMetadataRetriever - Metadata retriever initialized for: " + entry.getFullPath());
        return retriever;
    }

    public Observable<byte[]> getAlbumArt(MediaMetadata mm) {

        return Observable.create(subscriber -> {

            LogHelper.d(TAG, "getAlbumArt - Starting subscriber on thread: "+Thread.currentThread().getName());

            DropboxDBEntry entry = mEntryDao.findById(Long.parseLong(mm.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)));

            MediaMetadataRetriever retriever;
            if (entry != null) {

                DropboxDBSong song = mSongDao.findByEntryId(entry.getId());

                if (song != null && song.hasValidAlbumArt()) {

                    retriever = initializeMediaMetadataRetriever(entry, getCachedSongFile(entry));

                    if (retriever != null) {

                        byte[] bitmapByteArray = retriever.getEmbeddedPicture();

                        if (bitmapByteArray != null && bitmapByteArray.length > 0) {

                            subscriber.onNext(bitmapByteArray);

                        } else {

                            song.setHasValidAlbumArt(false);
                            mSongDao.insertOrReplace(song);

                            subscriber.onError(new Exception("File contains no image data."));
                            LogHelper.w(TAG, "getAlbumArt - Finished with error for: " + mm.getString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE));
                            return;
                        }
                    }
                }
            }

            subscriber.onCompleted();
            LogHelper.d(TAG, "getAlbumArt - Finished succesfully for: " + mm.getString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE));

        });

    }

    public
    @Nullable
    File getCachedSongFile(DropboxDBEntry entry) {
        return getCachedSongFile(entry, 0);
    }


    public
    @Nullable
    File getCachedSongFile(DropboxDBEntry entry, long timeout) {
        return mCachedSongs.get(SongCacheHelper.makeLRUCacheFileName(entry), timeout);
    }

    public
    @Nullable
    File getCachedSongFile(DropboxDBEntry entry, long timeout, int cacheFlag) {

        File cachedSongFile =
                mCachedSongs.get(SongCacheHelper.makeLRUCacheFileName(entry), timeout);

        if (cachedSongFile == null) {

            final DropboxDBSong song = entry.getSong();

            final boolean isCheapNetwork = !NetworkHelper.isNetworkMetered(mApplicationContext);
            final boolean isMetadataNeeded = (!song.hasLatestMetadata() &&
                    (cacheFlag & FLAG_SONG_METADATA_TEXT) == FLAG_SONG_METADATA_TEXT);
            final boolean willSongBePlayed = (cacheFlag & FLAG_SONG_PLAYABLE) == FLAG_SONG_PLAYABLE;

            if ((isCheapNetwork && (isMetadataNeeded || willSongBePlayed)) ||
                    (isMetadataNeeded && willSongBePlayed)) {

                LogHelper.d(TAG, "synchronizeSongDB - Attempting to save to cache for entry: " + entry.getFullPath());
                cachedSongFile = downloadSongDataIntoCache(entry);

            }
        }

        return cachedSongFile;
    }

    private class AsyncCacheRequest {
        private DropboxDBEntry entry;
        private int cacheFlags;

        public AsyncCacheRequest(DropboxDBEntry entry, int cacheFlags) {
            this.entry = entry;
            this.cacheFlags = cacheFlags;
        }

        public DropboxDBEntry getEntry() {
            return entry;
        }

        public int getCacheFlags() {
            return cacheFlags;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof AsyncCacheRequest) {
                AsyncCacheRequest that = (AsyncCacheRequest) o;
                return getEntry().getId() == that.getEntry().getId() && getCacheFlags() == that.getCacheFlags();
            }

            return false;
        }
    }

}
