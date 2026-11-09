#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <algorithm>
#include "llama.h"

static llama_model * g_model = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_stop_requested{false};

static std::string from_jstring(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_robat2_mobile_LocalModelEngine_loadModel(JNIEnv *env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_init();
    llama_model_params params = llama_model_default_params();
    params.use_mmap = true;
    const std::string model_path = from_jstring(env, path);
    g_model = llama_model_load_from_file(model_path.c_str(), params);
    return g_model ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_robat2_mobile_LocalModelEngine_generate(JNIEnv *env, jclass, jstring prompt_value, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return env->NewStringUTF("مدل هنوز بارگذاری نشده است.");
    g_stop_requested.store(false);
    const std::string prompt = from_jstring(env, prompt_value);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int token_count = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    if (token_count <= 0) return env->NewStringUTF("خطا در تبدیل متن به توکن.");
    if (token_count > 3800) return env->NewStringUTF("درخواست و سابقه گفتگو بیش از ظرفیت نسخه فعلی است؛ لطفاً متن کوتاه‌تری بفرست.");
    std::vector<llama_token> tokens((size_t)token_count);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), (int)tokens.size(), true, true) < 0)
        return env->NewStringUTF("خطا در پردازش درخواست.");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = std::min(512, std::max(32, token_count));
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    llama_context * ctx = llama_init_from_model(g_model, cp);
    if (!ctx) return env->NewStringUTF("حافظه کافی برای اجرای مدل در دسترس نیست.");

    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        llama_free(ctx);
        return env->NewStringUTF("مدل نتوانست متن ورودی را پردازش کند؛ درخواست را کوتاه‌تر کن.");
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string output;
    std::vector<char> piece(8192);
    const int limit = std::min(2048, std::max(1, (int)max_tokens));
    for (int i = 0; i < limit; ++i) {
        if (g_stop_requested.load()) break;
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        const int n = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, true);
        if (n > 0) output.append(piece.data(), (size_t)n);
        llama_sampler_accept(sampler, token);
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }
    llama_sampler_free(sampler);
    llama_free(ctx);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_robat2_mobile_LocalModelEngine_release(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_robat2_mobile_LocalModelEngine_requestStop(JNIEnv *, jclass) {
    g_stop_requested.store(true);
}
