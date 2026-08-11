/*
** filesystem.cpp
**
** This file is part of mkxp.
**
** Copyright (C) 2013 Jonas Kulla <Nyocurio@gmail.com>
**
** mkxp is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation, either version 2 of the License, or
** (at your option) any later version.
**
** mkxp is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with mkxp.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "filesystem.h"

#include "util/boost-hash.h"
#include "util/debugwriter.h"
#include "util/exception.h"
#include "util/util.h"
#include "display/font.h"
#include "crypto/rgssad.h"

#include "eventthread.h"
#include "sharedstate.h"

#include <physfs.h>

#include <algorithm>
#include <stack>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vector>

#ifdef __APPLE__
#include <iconv.h>
#endif

#ifdef __WIN32__
#include <direct.h>
#endif

#ifdef __ANDROID__
#include <SDL.h>
#include <jni.h>

// https://stackoverflow.com/a/46871051/13295652
static jobject getJNIGlobalContext(JNIEnv *env)
{
	jclass clsActivityThread = env->FindClass("android/app/ActivityThread");
	jmethodID midCurrentActivityThread = env->GetStaticMethodID(clsActivityThread, "currentActivityThread", "()Landroid/app/ActivityThread;");
	jobject activityThread = env->CallStaticObjectMethod(clsActivityThread, midCurrentActivityThread);

	jmethodID getApplication = env->GetMethodID(clsActivityThread, "getApplication", "()Landroid/app/Application;");
	jobject context = env->CallObjectMethod(activityThread, getApplication);

	env->DeleteLocalRef(clsActivityThread);
	env->DeleteLocalRef(activityThread);

	return context;
}
#endif

struct SDLRWIoContext
{
	SDL_RWops *ops;
	std::string filename;

	SDLRWIoContext(const char *filename)
		: ops(SDL_RWFromFile(filename, "r")), filename(filename)
	{
		if (!ops)
			throw Exception(Exception::SDLError, "Failed to open file: %s", SDL_GetError());
	}

	~SDLRWIoContext()
	{
		SDL_RWclose(ops);
	}
};

static PHYSFS_Io *createSDLRWIo(const char *filename);

static SDL_RWops *getSDLRWops(PHYSFS_Io *io) {
  return static_cast<SDLRWIoContext *>(io->opaque)->ops;
}

static PHYSFS_sint64 SDLRWIoRead(struct PHYSFS_Io *io, void *buf,
                                 PHYSFS_uint64 len) {
  return SDL_RWread(getSDLRWops(io), buf, 1, len);
}

static int SDLRWIoSeek(struct PHYSFS_Io *io, PHYSFS_uint64 offset) {
  return (SDL_RWseek(getSDLRWops(io), offset, RW_SEEK_SET) != -1);
}

static PHYSFS_sint64 SDLRWIoTell(struct PHYSFS_Io *io) {
  return SDL_RWseek(getSDLRWops(io), 0, RW_SEEK_CUR);
}

static PHYSFS_sint64 SDLRWIoLength(struct PHYSFS_Io *io) {
  return SDL_RWsize(getSDLRWops(io));
}

static struct PHYSFS_Io *SDLRWIoDuplicate(struct PHYSFS_Io *io) {
  SDLRWIoContext *ctx = static_cast<SDLRWIoContext *>(io->opaque);
  int64_t offset = io->tell(io);
  PHYSFS_Io *dup = createSDLRWIo(ctx->filename.c_str());

  if (dup)
    SDLRWIoSeek(dup, offset);

  return dup;
}

static void SDLRWIoDestroy(struct PHYSFS_Io *io) {
  delete static_cast<SDLRWIoContext *>(io->opaque);
  delete io;
}

static PHYSFS_Io SDLRWIoTemplate = {0,
                                    0, /* version, opaque */
                                    SDLRWIoRead,
                                    0, /* write */
                                    SDLRWIoSeek,
                                    SDLRWIoTell,
                                    SDLRWIoLength,
                                    SDLRWIoDuplicate,
                                    0, /* flush */
                                    SDLRWIoDestroy};

static PHYSFS_Io *createSDLRWIo(const char *filename) {
  SDLRWIoContext *ctx;

  try {
    ctx = new SDLRWIoContext(filename);
  } catch (const Exception &e) {
    Debug() << "Failed mounting" << filename;
    return 0;
  }

  PHYSFS_Io *io = new PHYSFS_Io;
  *io = SDLRWIoTemplate;
  io->opaque = ctx;

  return io;
}

static inline PHYSFS_File *sdlPHYS(SDL_RWops *ops) {
  return static_cast<PHYSFS_File *>(ops->hidden.unknown.data1);
}

static Sint64 SDL_RWopsSize(SDL_RWops *ops) {
  PHYSFS_File *f = sdlPHYS(ops);

  if (!f)
    return -1;

  return PHYSFS_fileLength(f);
}

static Sint64 SDL_RWopsSeek(SDL_RWops *ops, int64_t offset, int whence) {
  PHYSFS_File *f = sdlPHYS(ops);

  if (!f)
    return -1;

  int64_t base;

  switch (whence) {
  default:
  case RW_SEEK_SET:
    base = 0;
    break;
  case RW_SEEK_CUR:
    base = PHYSFS_tell(f);
    break;
  case RW_SEEK_END:
    base = PHYSFS_fileLength(f);
    break;
  }

  int result = PHYSFS_seek(f, base + offset);

  return (result != 0) ? PHYSFS_tell(f) : -1;
}

static size_t SDL_RWopsRead(SDL_RWops *ops, void *buffer, size_t size,
                            size_t maxnum) {
  PHYSFS_File *f = sdlPHYS(ops);

  if (!f)
    return 0;

  PHYSFS_sint64 result = PHYSFS_readBytes(f, buffer, size * maxnum);

  return (result != -1) ? (result / size) : 0;
}

static size_t SDL_RWopsWrite(SDL_RWops *ops, const void *buffer, size_t size,
                             size_t num) {
  PHYSFS_File *f = sdlPHYS(ops);

  if (!f)
    return 0;

  PHYSFS_sint64 result = PHYSFS_writeBytes(f, buffer, size * num);

  return (result != -1) ? (result / size) : 0;
}

static int SDL_RWopsClose(SDL_RWops *ops) {
  PHYSFS_File *f = sdlPHYS(ops);

  if (!f)
    return -1;

  int result = PHYSFS_close(f);
  ops->hidden.unknown.data1 = 0;

  return (result != 0) ? 0 : -1;
}

static int SDL_RWopsCloseFree(SDL_RWops *ops) {
  int result = SDL_RWopsClose(ops);

  SDL_FreeRW(ops);

  return result;
}

/* Copies the first srcN characters from src into dst,
 * or the full string if srcN == -1. Never writes more
 * than dstMax, and guarantees dst to be null terminated.
 * Returns copied bytes (minus terminating null) */
static size_t strcpySafe(char *dst, const char *src, size_t dstMax, int srcN) {
  if (srcN < 0)
    srcN = strlen(src);

  size_t cpyMax = std::min<size_t>(dstMax - 1, srcN);

  memcpy(dst, src, cpyMax);
  dst[cpyMax] = '\0';

  return cpyMax;
}

/* Attempt to locate an extension string in a filename.
 * Either a pointer into the input string pointing at the
 * extension, or null is returned */
static const char *findExt(const char *filename) {
  size_t len;

  for (len = strlen(filename); len > 0; --len) {
    if (filename[len] == '/')
      return 0;

    if (filename[len] == '.')
      return &filename[len + 1];
  }

  return 0;
}

static void initReadOps(PHYSFS_File *handle, SDL_RWops &ops, bool freeOnClose) {
  ops.size = SDL_RWopsSize;
  ops.seek = SDL_RWopsSeek;
  ops.read = SDL_RWopsRead;
  ops.write = SDL_RWopsWrite;

  if (freeOnClose)
    ops.close = SDL_RWopsCloseFree;
  else
    ops.close = SDL_RWopsClose;

  ops.type = SDL_RWOPS_PHYSFS;
  ops.hidden.unknown.data1 = handle;
}

static void strTolower(std::string &str) {
  for (size_t i = 0; i < str.size(); ++i)
    str[i] = tolower(str[i]);
}

const Uint32 SDL_RWOPS_PHYSFS = SDL_RWOPS_UNKNOWN + 10;

struct FileSystemPrivate {
  /* Maps: lower case full filepath,
   * To:   mixed case full filepath */
  BoostHash<std::string, std::string> pathCache;
  /* Maps: lower case directory path,
   * To:   list of lower case filenames */
  BoostHash<std::string, std::vector<std::string>> fileLists;

  /* This is for compatibility with games that take Windows'
   * case insensitivity for granted */
  bool havePathCache;
};

static void throwPhysfsError(const char *desc) {
  PHYSFS_ErrorCode ec = PHYSFS_getLastErrorCode();
  const char *englishStr = PHYSFS_getErrorByCode(ec);

  throw Exception(Exception::PHYSFSError, "%s: %s", desc, englishStr);
}

FileSystem::FileSystem(const char *argv0, bool allowSymlinks)
{
#ifdef __ANDROID__
	// Get JNI environment and context
	JNIEnv *jenv = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject jctx = getJNIGlobalContext(jenv);

	// PHYSFS_AndroidInit struct
	PHYSFS_AndroidInit ainit;
	ainit.jnienv = jenv;
	ainit.context = jctx;

	if (PHYSFS_init((char *)&ainit) == 0)
		throwPhysfsError("Error initializing PhysFS");
#else
	if (PHYSFS_init(argv0) == 0)
		throwPhysfsError("Error initializing PhysFS");
#endif

	// One error (=return 0) turns the whole product to 0
	int er = 1;

	er *= PHYSFS_registerArchiver(&RGSS1_Archiver);
	er *= PHYSFS_registerArchiver(&RGSS2_Archiver);
	er *= PHYSFS_registerArchiver(&RGSS3_Archiver);

	if (er == 0)
		throwPhysfsError("Error registering PhysFS RGSS archiver");

	p = new FileSystemPrivate;
	p->havePathCache = false;

	if (allowSymlinks)
		PHYSFS_permitSymbolicLinks(1);
}

FileSystem::~FileSystem()
{
	delete p;

	if (PHYSFS_deinit() == 0)
		Debug() << "PhyFS failed to deinit.";
}

void FileSystem::addPath(const char *path, const char *mountpoint, bool reload) {
  /* Try the normal mount first */
    int state = PHYSFS_mount(path, mountpoint, 1);
  if (!state) {
    /* If it didn't work, try mounting via a wrapped
     * SDL_RWops */
    PHYSFS_Io *io = createSDLRWIo(path);

    if (io)
      state = PHYSFS_mountIo(io, path, 0, 1);
  }
    if (!state) {
        PHYSFS_ErrorCode err = PHYSFS_getLastErrorCode();
        throw Exception(Exception::PHYSFSError, "Failed to mount %s (%s)", path, PHYSFS_getErrorByCode(err));
    }
    
    if (reload) reloadPathCache();
}

void FileSystem::removePath(const char *path, bool reload) {
    
    if (!PHYSFS_unmount(path)) {
        PHYSFS_ErrorCode err = PHYSFS_getLastErrorCode();
        throw Exception(Exception::PHYSFSError, "Failed to unmount %s (%s)", path, PHYSFS_getErrorByCode(err));
    }
    
    if (reload) reloadPathCache();
}

/* Persistent path cache.
 *
 * Building the cache calls PHYSFS_stat() on every file in the game
 * directory. On Android that goes through FUSE, and with ~22000 files it
 * takes over a minute on every single launch. The result depends only on
 * which files exist, so it is written to disk once and reloaded afterwards.
 *
 * Validity is checked against the modification time of every directory that
 * was walked: adding, removing or renaming a file changes the mtime of its
 * parent directory. File *contents* may change freely, which is correct,
 * since the cache only maps names. Files added directly in the game root are
 * not detected; delete the cache file to force a rebuild. */
#define PATH_CACHE_FILE ".mkxp_pathcache"
#define PATH_CACHE_MAGIC "mkxp-z path cache 1"

/* One directory as stored in the cache file: its mixed case path, its mtime,
 * and its filenames in enumeration order. Both live maps are rebuilt from
 * this, so the reloaded cache is identical to a freshly scanned one,
 * including the order in which directory listings are returned. */
struct DirCacheEntry {
  std::string mixedPath;
  PHYSFS_sint64 modtime;
  std::vector<std::string> files;
};

struct CacheEnumData {
  FileSystemPrivate *p;
  std::stack<std::vector<std::string> *> fileLists;

  /* collected while scanning, only to be written to the cache file.
   * Indices, not pointers: the vector reallocates as it grows. */
  std::vector<DirCacheEntry> dirs;
  std::stack<size_t> dirStack;

#ifdef __APPLE__
  iconv_t nfd2nfc;
  char buf[512];
#endif

  CacheEnumData(FileSystemPrivate *p) : p(p) {
#ifdef __APPLE__
    nfd2nfc = iconv_open("utf-8", "utf-8-mac");
#endif
  }

  ~CacheEnumData() {
#ifdef __APPLE__
    iconv_close(nfd2nfc);
#endif
  }

  /* Converts in-place */
  void toNFC(char *inout) {
#ifdef __APPLE__
    size_t srcSize = strlen(inout);
    size_t bufSize = sizeof(buf);
    char *bufPtr = buf;
    char *inoutPtr = inout;

    /* Reserve room for null terminator */
    --bufSize;

    iconv(nfd2nfc, &inoutPtr, &srcSize, &bufPtr, &bufSize);
    /* Null-terminate */
    *bufPtr = 0;
    strcpy(inout, buf);
#else
    (void)inout;
#endif
  }
};

static PHYSFS_EnumerateCallbackResult cacheEnumCB(void *d, const char *origdir,
                                                  const char *fname) {
  CacheEnumData &data = *static_cast<CacheEnumData *>(d);
  char fullPath[512];

  if (!*origdir)
    snprintf(fullPath, sizeof(fullPath), "%s", fname);
  else
    snprintf(fullPath, sizeof(fullPath), "%s/%s", origdir, fname);

  /* Deal with OSX' weird UTF-8 standards */
  data.toNFC(fullPath);

  std::string mixedCase(fullPath);
  std::string lowerCase = mixedCase;
  strTolower(lowerCase);

  PHYSFS_Stat stat;
  PHYSFS_stat(fullPath, &stat);

  if (stat.filetype == PHYSFS_FILETYPE_DIRECTORY) {
    /* Create a new list for this directory */
    std::vector<std::string> &list = data.p->fileLists[lowerCase];

    /* Record it for the cache file, with the mtime used to validate it */
    DirCacheEntry entry;
    entry.mixedPath = mixedCase;
    entry.modtime = stat.modtime;
    data.dirs.push_back(entry);
    size_t index = data.dirs.size() - 1;

    /* Iterate over its contents */
    data.fileLists.push(&list);
    data.dirStack.push(index);
    PHYSFS_enumerate(fullPath, cacheEnumCB, d);
    data.dirStack.pop();
    data.fileLists.pop();
  } else {
    /* Get the file list for the directory we're currently
     * traversing and append this filename to it */
    std::vector<std::string> &list = *data.fileLists.top();

    std::string lowerFilename(fname);
    strTolower(lowerFilename);
    list.push_back(lowerFilename);

    /* Add the lower -> mixed mapping of the file's full path */
    data.p->pathCache.insert(lowerCase, mixedCase);

    if (!data.dirStack.empty())
      data.dirs[data.dirStack.top()].files.push_back(std::string(fname));
  }

  return PHYSFS_ENUM_OK;
}

static void stripEol(char *s) {
  size_t len = strlen(s);
  while (len > 0 && (s[len - 1] == '\n' || s[len - 1] == '\r'))
    s[--len] = 0;
}

/* Rebuilds both maps from a cache file. Returns false and leaves the maps
 * untouched if the file is missing, unreadable, of an unknown version, or if
 * any recorded directory has changed since it was written. */
static bool loadPathCacheFile(FileSystemPrivate *p) {
  FILE *f = fopen(PATH_CACHE_FILE, "rb");
  if (!f)
    return false;

  bool ok = false;
  std::vector<DirCacheEntry> dirs;

  do {
    char line[1024];
    if (!fgets(line, sizeof(line), f))
      break;
    if (strncmp(line, PATH_CACHE_MAGIC, strlen(PATH_CACHE_MAGIC)) != 0)
      break;

    long nDirs = 0;
    if (!fgets(line, sizeof(line), f) || sscanf(line, "%ld", &nDirs) != 1)
      break;
    if (nDirs < 0 || nDirs > 100000)
      break;

    /* Parsed line by line, never with scanf: in a scanf format a literal tab
     * matches ANY run of whitespace, newlines included, so on the game root
     * entry -- whose path is empty -- it would swallow the following line. */
    bool bad = false;
    for (long i = 0; i < nDirs && !bad; ++i) {
      if (!fgets(line, sizeof(line), f)) {
        bad = true;
        break;
      }
      stripEol(line);

      /* <modtime> TAB <nfiles> TAB <path>, path possibly empty */
      char *tab1 = strchr(line, '\t');
      if (!tab1) {
        bad = true;
        break;
      }
      char *tab2 = strchr(tab1 + 1, '\t');
      if (!tab2) {
        bad = true;
        break;
      }
      *tab1 = 0;
      *tab2 = 0;

      DirCacheEntry entry;
      entry.modtime = (PHYSFS_sint64)atoll(line);
      long nFiles = atol(tab1 + 1);
      entry.mixedPath = tab2 + 1;

      if (nFiles < 0 || nFiles > 1000000) {
        bad = true;
        break;
      }

      for (long j = 0; j < nFiles; ++j) {
        if (!fgets(line, sizeof(line), f)) {
          bad = true;
          break;
        }
        stripEol(line);
        entry.files.push_back(std::string(line));
      }
      if (bad)
        break;
      dirs.push_back(entry);
    }
    if (bad)
      break;

    /* Every recorded directory must still exist, still be a directory, and
     * still have the same mtime. The game root is skipped: creating the cache
     * file itself changes its mtime. */
    for (size_t i = 0; i < dirs.size(); ++i) {
      if (dirs[i].mixedPath.empty())
        continue;
      PHYSFS_Stat st;
      if (!PHYSFS_stat(dirs[i].mixedPath.c_str(), &st)) {
        bad = true;
        break;
      }
      if (st.filetype != PHYSFS_FILETYPE_DIRECTORY ||
          st.modtime != dirs[i].modtime) {
        bad = true;
        break;
      }
    }
    if (bad)
      break;

    ok = true;
  } while (0);

  fclose(f);

  if (!ok)
    return false;

  for (size_t i = 0; i < dirs.size(); ++i) {
    const DirCacheEntry &entry = dirs[i];
    std::string dirLower = entry.mixedPath;
    strTolower(dirLower);

    /* creates the entry even for directories holding no files, which is what
     * a fresh scan does too */
    std::vector<std::string> &list = p->fileLists[dirLower];

    for (size_t j = 0; j < entry.files.size(); ++j) {
      std::string mixed = entry.mixedPath.empty()
                              ? entry.files[j]
                              : entry.mixedPath + "/" + entry.files[j];
      std::string lower = mixed;
      strTolower(lower);
      p->pathCache.insert(lower, mixed);

      std::string nameLower = entry.files[j];
      strTolower(nameLower);
      list.push_back(nameLower);
    }
  }

  return true;
}

static void savePathCacheFile(const std::vector<DirCacheEntry> &dirs) {
  /* Written next to the game data, so it travels with the copy it describes.
   * A failure here is not fatal: the cache is just rebuilt next time. */
  FILE *f = fopen(PATH_CACHE_FILE, "wb");
  if (!f)
    return;

  fprintf(f, "%s\n", PATH_CACHE_MAGIC);
  fprintf(f, "%ld\n", (long)dirs.size());
  for (size_t i = 0; i < dirs.size(); ++i) {
    fprintf(f, "%lld\t%ld\t%s\n", (long long)dirs[i].modtime,
            (long)dirs[i].files.size(), dirs[i].mixedPath.c_str());
    for (size_t j = 0; j < dirs[i].files.size(); ++j)
      fprintf(f, "%s\n", dirs[i].files[j].c_str());
  }

  fclose(f);
}

/* The actual scan: one PHYSFS_stat per file. */
static void buildPathCache(FileSystemPrivate *p) {
  CacheEnumData data(p);

  /* the game root, so that files sitting directly in it are recorded too */
  DirCacheEntry root;
  root.mixedPath = "";
  root.modtime = 0;
  data.dirs.push_back(root);
  data.dirStack.push(0);

  data.fileLists.push(&p->fileLists[""]);
  PHYSFS_enumerate("", cacheEnumCB, &data);

  savePathCacheFile(data.dirs);
}

void FileSystem::createPathCache() {
  if (!loadPathCacheFile(p)) {
    p->fileLists.clear();
    p->pathCache.clear();
    buildPathCache(p);
  }

  p->havePathCache = true;
}

void FileSystem::reloadPathCache() {
    if (!p->havePathCache) return;

    p->fileLists.clear();
    p->pathCache.clear();
    /* an explicit reload must not trust the file on disk */
    buildPathCache(p);
}

struct FontSetsCBData {
  FileSystemPrivate *p;
  SharedFontState *sfs;
};

static PHYSFS_EnumerateCallbackResult fontSetEnumCB(void *data, const char *dir,
                                                    const char *fname) {
  FontSetsCBData *d = static_cast<FontSetsCBData *>(data);

  /* Only consider filenames with font extensions */
  const char *ext = findExt(fname);

  if (!ext)
    return PHYSFS_ENUM_OK;

  char lowExt[8];
  size_t i;

  for (i = 0; i < sizeof(lowExt) - 1 && ext[i]; ++i)
    lowExt[i] = tolower(ext[i]);
  lowExt[i] = '\0';

  if (strcmp(lowExt, "ttf") && strcmp(lowExt, "otf"))
    return PHYSFS_ENUM_OK;

  char filename[512];
  snprintf(filename, sizeof(filename), "%s/%s", dir, fname);

  PHYSFS_File *handle = PHYSFS_openRead(filename);

  if (!handle)
    return PHYSFS_ENUM_ERROR;

  SDL_RWops ops;
  initReadOps(handle, ops, false);

  d->sfs->initFontSetCB(ops, filename);

  SDL_RWclose(&ops);

  return PHYSFS_ENUM_OK;
}

/* Basically just a case-insensitive search
 * for the folder "Fonts"... */
static PHYSFS_EnumerateCallbackResult
findFontsFolderCB(void *data, const char *, const char *fname) {
  size_t i = 0;
  char buffer[512];
  const char *s = fname;

  while (*s && i < sizeof(buffer))
    buffer[i++] = tolower(*s++);

  buffer[i] = '\0';

  if (strcmp(buffer, "fonts") == 0)
    PHYSFS_enumerate(fname, fontSetEnumCB, data);

  return PHYSFS_ENUM_OK;
}

void FileSystem::initFontSets(SharedFontState &sfs) {
  FontSetsCBData d = {p, &sfs};

  PHYSFS_enumerate("", findFontsFolderCB, &d);
}

struct OpenReadEnumData {
  FileSystem::OpenHandler &handler;
  SDL_RWops ops;

  /* The filename (without directory) we're looking for */
  const char *filename;
  size_t filenameN;

  /* Optional hash to translate full filepaths
   * (used with path cache) */
  BoostHash<std::string, std::string> *pathTrans;

  /* Number of files we've attempted to read and parse */
  size_t matchCount;
  bool stopSearching;

  /* In case of a PhysFS error, save it here so it
   * doesn't get changed before we get back into our code */
  const char *physfsError;

  OpenReadEnumData(FileSystem::OpenHandler &handler, const char *filename,
                   size_t filenameN,
                   BoostHash<std::string, std::string> *pathTrans)
      : handler(handler), filename(filename), filenameN(filenameN),
        pathTrans(pathTrans), matchCount(0), stopSearching(false),
        physfsError(0) {}
};

static PHYSFS_EnumerateCallbackResult
openReadEnumCB(void *d, const char *dirpath, const char *filename) {
  OpenReadEnumData &data = *static_cast<OpenReadEnumData *>(d);
  char buffer[512];
  const char *fullPath;

  if (data.stopSearching)
    return PHYSFS_ENUM_STOP;

  /* If there's not even a partial match, continue searching */
  if (strncmp(filename, data.filename, data.filenameN) != 0)
    return PHYSFS_ENUM_OK;

  if (!*dirpath) {
    fullPath = filename;
  } else {
    snprintf(buffer, sizeof(buffer), "%s/%s", dirpath, filename);
    fullPath = buffer;
  }

  char last = filename[data.filenameN];
  /* If fname matches up to a following '.' (meaning the rest is part
   * of the extension), or up to a following '\0' (full match), we've
   * found our file */
  if (last != '.' && last != '\0')
    return PHYSFS_ENUM_OK;

  /* If the path cache is active, translate from lower case
   * to mixed case path */
  if (data.pathTrans)
    fullPath = (*data.pathTrans)[fullPath].c_str();

  PHYSFS_File *phys = PHYSFS_openRead(fullPath);

  if (!phys) {
    /* Failing to open this file here means there must
     * be a deeper rooted problem somewhere within PhysFS.
     * Just abort alltogether. */
    data.stopSearching = true;
    data.physfsError = PHYSFS_getErrorByCode(PHYSFS_getLastErrorCode());

    return PHYSFS_ENUM_ERROR;
  }
  initReadOps(phys, data.ops, false);

  const char *ext = findExt(filename);

  if (data.handler.tryRead(data.ops, ext))
    data.stopSearching = true;

  ++data.matchCount;
  return PHYSFS_ENUM_OK;
}

void FileSystem::openRead(OpenHandler &handler, const char *filename) {
  std::string filename_nm = normalize(filename, false, false);
  char buffer[512];
  size_t len = strcpySafe(buffer, filename_nm.c_str(), sizeof(buffer), -1);
  char *delim;

  if (p->havePathCache)
    for (size_t i = 0; i < len; ++i)
      buffer[i] = tolower(buffer[i]);

  /* Find the deliminator separating directory and file name */
  for (delim = buffer + len; delim > buffer; --delim)
    if (*delim == '/')
      break;

  const bool root = (delim == buffer);

  const char *file = buffer;
  const char *dir = "";

  if (!root) {
    /* Cut the buffer in half so we can use it
     * for both filename and directory path */
    *delim = '\0';
    file = delim + 1;
    dir = buffer;
  }
  OpenReadEnumData data(handler, file, len + buffer - delim - !root,
                        p->havePathCache ? &p->pathCache : 0);

  if (p->havePathCache) {
    /* Get the list of files contained in this directory
     * and manually iterate over them */
    const std::vector<std::string> &fileList = p->fileLists[dir];

    for (size_t i = 0; i < fileList.size(); ++i)
      openReadEnumCB(&data, dir, fileList[i].c_str());
  } else {
    PHYSFS_enumerate(dir, openReadEnumCB, &data);
  }

  if (data.physfsError)
    throw Exception(Exception::PHYSFSError, "PhysFS: %s", data.physfsError);

  if (data.matchCount == 0)
    throw Exception(Exception::NoFileError, "%s", filename);
}

void FileSystem::openReadRaw(SDL_RWops &ops, const char *filename,
                             bool freeOnClose) {

  PHYSFS_File *handle = PHYSFS_openRead(normalize(filename, 0, 0).c_str());

  if (!handle)
    throw Exception(Exception::NoFileError, "%s", filename);

  initReadOps(handle, ops, freeOnClose);
    return;
}

std::string FileSystem::normalize(const char *pathname, bool preferred,
                            bool absolute) {
    return filesystemImpl::normalizePath(pathname, preferred, absolute);
}

bool FileSystem::exists(const char *filename) {
  return PHYSFS_exists(normalize(filename, false, false).c_str());
}

const char *FileSystem::desensitize(const char *filename) {
  std::string fn_lower(filename);
    
  std::transform(fn_lower.begin(), fn_lower.end(), fn_lower.begin(), [](unsigned char c){
      return std::tolower(c);
  });
  if (p->havePathCache && p->pathCache.contains(fn_lower))
    return p->pathCache[fn_lower].c_str();
  return filename;
}
