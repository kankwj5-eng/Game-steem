/* DroidDeck JNI bridge. Upstream public-domain 7-Zip decoder lives separately in third_party. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <stddef.h>
#include "7z.h"
#include "7zCrc.h"
#include "7zFile.h"

#define INPUT_BYTES (256U * 1024U)
#define HEADER_LIMIT (64U * 1024U * 1024U)
#define DECODER_LIMIT ((size_t)640U * 1024U * 1024U)
#define MAX_BLOCK ((UInt64)512U * 1024U * 1024U)
#define MAX_FILES 100000U
#define MAX_PATH_BYTES 4096U

typedef union { max_align_t alignment; size_t bytes; } Allocation;
typedef struct { ISzAlloc api; size_t used, peak, limit; } Budget;
static void *budget_alloc(ISzAllocPtr api, size_t bytes) {
    Budget *budget = (Budget *)api;
    if (!bytes || bytes > budget->limit - budget->used || bytes > SIZE_MAX - sizeof(Allocation)) return NULL;
    Allocation *allocation = malloc(sizeof(Allocation) + bytes);
    if (!allocation) return NULL;
    allocation->bytes = bytes;
    budget->used += bytes;
    if (budget->used > budget->peak) budget->peak = budget->used;
    return allocation + 1;
}
static void budget_free(ISzAllocPtr api, void *pointer) {
    if (!pointer) return;
    Budget *budget = (Budget *)api;
    Allocation *allocation = (Allocation *)pointer - 1;
    budget->used -= allocation->bytes;
    free(allocation);
}

typedef struct {
    CFileInStream file;
    CLookToRead2 look;
    CSzArEx archive;
    Budget budget;
} Reader;
static pthread_once_t crc_once = PTHREAD_ONCE_INIT;
static SRes open_reader(Reader *reader, const char *path) {
    memset(reader, 0, sizeof(*reader));
    reader->budget.api.Alloc = budget_alloc;
    reader->budget.api.Free = budget_free;
    reader->budget.limit = HEADER_LIMIT;
    File_Construct(&reader->file.file);
    SzArEx_Init(&reader->archive);
    if (InFile_Open(&reader->file.file, path)) return SZ_ERROR_READ;
    FileInStream_CreateVTable(&reader->file);
    LookToRead2_CreateVTable(&reader->look, False);
    reader->look.buf = budget_alloc(&reader->budget.api, INPUT_BYTES);
    if (!reader->look.buf) return SZ_ERROR_MEM;
    reader->look.bufSize = INPUT_BYTES;
    reader->look.realStream = &reader->file.vt;
    LookToRead2_INIT(&reader->look)
    pthread_once(&crc_once, CrcGenerateTable);
    return SzArEx_Open(&reader->archive, &reader->look.vt, &reader->budget.api, &reader->budget.api);
}
static void close_reader(Reader *reader) {
    SzArEx_Free(&reader->archive, &reader->budget.api);
    budget_free(&reader->budget.api, reader->look.buf);
    File_Close(&reader->file.file);
}
static void fail(JNIEnv *env, const char *detail, int code) {
    if ((*env)->ExceptionCheck(env)) return;
    char message[256];
    snprintf(message, sizeof(message), "%s (código %d)", detail, code);
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message);
}

/* The pinned Steam asset uses ASCII names; reject unsupported names rather than altering a path. */
static int entry_name(const CSzArEx *archive, UInt32 index, char path[MAX_PATH_BYTES]) {
    size_t length = SzArEx_GetFileNameUtf16(archive, index, NULL);
    if (!length || length > MAX_PATH_BYTES) return 0;
    UInt16 name[MAX_PATH_BYTES];
    SzArEx_GetFileNameUtf16(archive, index, name);
    for (size_t i = 0; i < length; i++) {
        if (name[i] > 127 || (name[i] && name[i] < 32) || name[i] == '\\' || name[i] == ':') return 0;
        path[i] = (char)name[i];
    }
    if (strcmp(path, "Steam") && strncmp(path, "Steam/", 6)) return 0;
    const char *part = path;
    while (*part) {
        const char *slash = strchr(part, '/');
        size_t count = slash ? (size_t)(slash - part) : strlen(part);
        if (!count || (count == 1 && part[0] == '.') || (count == 2 && part[0] == '.' && part[1] == '.')) return 0;
        if (!slash) break;
        part = slash + 1;
        if (!*part) return 0;
    }
    return 1;
}
static int inspect_reader(Reader *reader, jlong result[4]) {
    CSzArEx *archive = &reader->archive;
    if (archive->NumFiles > MAX_FILES) return 0;
    result[0] = archive->NumFiles;
    for (UInt32 i = 0; i < archive->db.NumFolders; i++) {
        UInt64 bytes = SzAr_GetFolderUnpackSize(&archive->db, i);
        if (bytes > MAX_BLOCK) return 0;
        if (bytes > (UInt64)result[3]) result[3] = (jlong)bytes;
    }
    for (UInt32 i = 0; i < archive->NumFiles; i++) {
        char path[MAX_PATH_BYTES];
        if (!entry_name(archive, i, path)) return 0;
        if (!SzArEx_IsDir(archive, i)) {
            UInt64 bytes = SzArEx_GetFileSize(archive, i);
            if (bytes > MAX_BLOCK || bytes > (UInt64)INT64_MAX - (UInt64)result[1]) return 0;
            result[1] += (jlong)bytes;
            if (!strcmp(path, "Steam/steam.exe")) result[2] = 1;
        }
    }
    return result[2] == 1;
}

/* Traverse relative to staging, refusing symlinks at every component. No path escapes staging. */
static int open_output(int root, const char *path, int directory) {
    char copy[MAX_PATH_BYTES];
    memcpy(copy, path, strlen(path) + 1);
    int parent = dup(root);
    if (parent < 0) return -1;
    char *part = copy;
    while (1) {
        char *slash = strchr(part, '/');
        if (slash) *slash = 0;
        if (slash || directory) {
            if (mkdirat(parent, part, 0700) && errno != EEXIST) { close(parent); return -1; }
            int next = openat(parent, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            close(parent);
            if (next < 0) return -1;
            if (!slash) return next;
            parent = next;
            part = slash + 1;
        }
        else {
            int output = openat(parent, part, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
            close(parent);
            return output;
        }
    }
}
static int write_all(int fd, const Byte *data, size_t bytes) {
    while (bytes) {
        size_t count = bytes < 1024U * 1024U ? bytes : 1024U * 1024U;
        ssize_t written = write(fd, data, count);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return 0;
        data += written;
        bytes -= (size_t)written;
    }
    return fsync(fd) == 0;
}
static uint64_t millis(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
}

JNIEXPORT jlongArray JNICALL Java_com_winlator_console_NativeSteamArchive_inspect(
        JNIEnv *env, jclass type, jstring archive_path) {
    (void)type;
    if (!archive_path) { fail(env, "Ruta de paquete ausente", 0); return NULL; }
    const char *path = (*env)->GetStringUTFChars(env, archive_path, NULL);
    if (!path) return NULL;
    Reader reader;
    SRes status = open_reader(&reader, path);
    (*env)->ReleaseStringUTFChars(env, archive_path, path);
    jlong summary[4] = {0};
    if (status == SZ_OK && !inspect_reader(&reader, summary)) status = SZ_ERROR_UNSUPPORTED;
    close_reader(&reader);
    if (status != SZ_OK) { fail(env, "No se pudo leer el paquete Steam de forma segura", status); return NULL; }
    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 4, summary);
    return result;
}

JNIEXPORT jlong JNICALL Java_com_winlator_console_NativeSteamArchive_extract(
        JNIEnv *env, jclass type, jstring archive_path, jstring staging_path, jobject progress) {
    (void)type;
    if (!archive_path || !staging_path || !progress) { fail(env, "Parámetros de extracción ausentes", 0); return 0; }
    jclass callback_type = (*env)->GetObjectClass(env, progress);
    jmethodID callback = (*env)->GetMethodID(env, callback_type, "onProgress", "(Ljava/lang/String;IJJJ)V");
    (*env)->DeleteLocalRef(env, callback_type);
    if (!callback) return 0;
    const char *stage = (*env)->GetStringUTFChars(env, staging_path, NULL);
    if (!stage) return 0;
    int root = open(stage, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, staging_path, stage);
    if (root < 0) { fail(env, "No se pudo abrir la extracción temporal", errno); return 0; }
    const char *path = (*env)->GetStringUTFChars(env, archive_path, NULL);
    if (!path) { close(root); return 0; }
    Reader reader;
    SRes status = open_reader(&reader, path);
    (*env)->ReleaseStringUTFChars(env, archive_path, path);
    jlong summary[4] = {0};
    if (status == SZ_OK && !inspect_reader(&reader, summary)) status = SZ_ERROR_UNSUPPORTED;
    reader.budget.limit = DECODER_LIMIT;
    Byte *block = NULL;
    size_t block_size = 0;
    UInt32 block_index = 0xFFFFFFFF;
    jlong total = 0;
    jint files = 0;
    uint64_t last_update = 0;
    for (UInt32 i = 0; status == SZ_OK && i < reader.archive.NumFiles; i++) {
        char name[MAX_PATH_BYTES];
        if (!entry_name(&reader.archive, i, name)) { status = SZ_ERROR_UNSUPPORTED; break; }
        int directory = SzArEx_IsDir(&reader.archive, i);
        int output = open_output(root, name, directory);
        if (output < 0) { status = SZ_ERROR_WRITE; break; }
        if (directory) { close(output); continue; }
        size_t offset = 0, bytes = 0;
        status = SzArEx_Extract(&reader.archive, &reader.look.vt, i, &block_index,
                &block, &block_size, &offset, &bytes, &reader.budget.api, &reader.budget.api);
        if (status == SZ_OK && (bytes != SzArEx_GetFileSize(&reader.archive, i)
                || offset > block_size || bytes > block_size - offset)) status = SZ_ERROR_DATA;
        if (status == SZ_OK && !write_all(output, bytes ? block + offset : NULL, bytes)) status = SZ_ERROR_WRITE;
        if (close(output) && status == SZ_OK) status = SZ_ERROR_WRITE;
        if (status != SZ_OK) break;
        total += (jlong)bytes;
        files++;
        uint64_t now = millis();
        if (now - last_update >= 250U || total == summary[1]) {
            last_update = now;
            jstring current = (*env)->NewStringUTF(env, name);
            if (!current) { status = SZ_ERROR_MEM; break; }
            (*env)->CallVoidMethod(env, progress, callback, current, files, total, summary[1], (jlong)reader.budget.peak);
            (*env)->DeleteLocalRef(env, current);
            if ((*env)->ExceptionCheck(env)) { status = SZ_ERROR_FAIL; break; }
        }
    }
    if (status == SZ_OK && total != summary[1]) status = SZ_ERROR_DATA;
    budget_free(&reader.budget.api, block);
    jlong peak = (jlong)reader.budget.peak;
    close_reader(&reader);
    close(root);
    if (status != SZ_OK) fail(env, status == SZ_ERROR_MEM ? "Memoria nativa insuficiente para extraer Steam"
            : "La extracción de Steam no quedó completa", status);
    return peak;
}
