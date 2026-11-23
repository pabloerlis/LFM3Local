#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <deque>
#include <cstdint>
#include <limits>
#include <chrono>
#include <stdexcept>
#include <utility>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.1f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static common_batch                       g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

// Warm LRU cache: keep bounded KV snapshots for previously visited chats.
// Snapshot data are private process memory, tied to the loaded GGUF and
// conversation revision; never persisted or exposed to untrusted code.
struct context_snapshot {
    int64_t chat_id = 0;
    int64_t revision = 0;
    std::string config_key;
    std::vector<uint8_t> kv;
    std::vector<common_chat_msg> messages;
    llama_pos current_pos = 0;
    llama_pos system_pos = 0;
};
constexpr size_t SNAPSHOT_TOTAL_LIMIT = 160ULL * 1024ULL * 1024ULL;
constexpr size_t SNAPSHOT_SINGLE_LIMIT = 120ULL * 1024ULL * 1024ULL;
constexpr size_t SNAPSHOT_MAX_ITEMS = 2;
static std::deque<context_snapshot> g_snapshots;
static size_t g_snapshot_bytes = 0;

static void snapshot_clear_all() {
    g_snapshots.clear();
    g_snapshot_bytes = 0;
}

static void snapshot_drop_chat(int64_t chat_id) {
    for (auto it = g_snapshots.begin(); it != g_snapshots.end();) {
        if (it->chat_id == chat_id) {
            g_snapshot_bytes -= it->kv.size();
            it = g_snapshots.erase(it);
        } else ++it;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_init_from_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = 50;
    sparams.penalty_repeat = 1.1f;
    sparams.min_p = 0.0f;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    snapshot_clear_all();  // snapshots belong to one loaded model
    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch batch(context);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            batch.add(0, i, 0, false);
        }

        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            batch.clear();
            for (j = 0; j < pl; j++) {
                batch.add(0, i, j, true);
            }

            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}



// Create a verified, size-bounded state snapshot without replaying history.
// Only invoked after a fully completed turn, never during sampling.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_cacheConversationNative(
        JNIEnv * env, jobject, jlong chat_id, jlong revision, jstring jconfig_key) {
    if (!g_context || !g_model || !g_sampler || chat_msgs.empty() ||
        current_position <= 0 || chat_id <= 0 || !jconfig_key) return JNI_FALSE;
    const char * raw = env->GetStringUTFChars(jconfig_key, nullptr);
    const std::string key(raw);
    env->ReleaseStringUTFChars(jconfig_key, raw);
    if (key.size() != 64) return JNI_FALSE;

    try {
        // Exact size per sequence from the pinned llama.cpp API.
        const size_t required = llama_state_seq_get_size(g_context, 0);
        if (required == 0 || required > SNAPSHOT_SINGLE_LIMIT) {
            LOGi("KV snapshot skipped: size %zu exceeds budget", required);
            snapshot_drop_chat((int64_t)chat_id);
            return JNI_FALSE;
        }
        context_snapshot snap;
        snap.chat_id = (int64_t)chat_id;
        snap.revision = (int64_t)revision;
        snap.config_key = key;
        snap.messages = chat_msgs;
        snap.current_pos = current_position;
        snap.system_pos = system_prompt_position;
        snap.kv.resize(required);
        const size_t copied = llama_state_seq_get_data(
            g_context, snap.kv.data(), snap.kv.size(), 0);
        if (copied == 0 || copied > required) return JNI_FALSE;
        snap.kv.resize(copied);
        snapshot_drop_chat(snap.chat_id);
        while (!g_snapshots.empty() &&
                (g_snapshots.size() >= SNAPSHOT_MAX_ITEMS ||
                 g_snapshot_bytes + snap.kv.size() > SNAPSHOT_TOTAL_LIMIT)) {
            g_snapshot_bytes -= g_snapshots.front().kv.size();
            g_snapshots.pop_front();
        }
        g_snapshot_bytes += snap.kv.size();
        g_snapshots.push_back(std::move(snap));
        LOGi("KV snapshot stored for chat %lld, %zu bytes, %zu retained",
             (long long)chat_id, copied, g_snapshots.size());
        return JNI_TRUE;
    } catch (const std::bad_alloc &) {
        LOGw("KV snapshot allocation failed; replay fallback retained");
        snapshot_drop_chat((int64_t)chat_id);
        return JNI_FALSE;
    } catch (const std::exception &ex) {
        LOGw("KV snapshot failed: %s", ex.what());
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_restoreCachedConversationNative(
        JNIEnv * env, jobject, jlong chat_id, jlong revision, jstring jconfig_key) {
    if (!g_context || !g_model || !g_sampler || chat_id <= 0 || !jconfig_key)
        return JNI_FALSE;
    const char * raw = env->GetStringUTFChars(jconfig_key, nullptr);
    const std::string key(raw);
    env->ReleaseStringUTFChars(jconfig_key, raw);
    if (key.size() != 64) return JNI_FALSE;
    const auto it = std::find_if(g_snapshots.begin(), g_snapshots.end(),
        [&](const context_snapshot & s) {
            return s.chat_id == (int64_t)chat_id &&
                   s.revision == (int64_t)revision &&
                   s.config_key == key;
        });
    if (it == g_snapshots.end()) return JNI_FALSE;
    try {
        llama_memory_clear(llama_get_memory(g_context), false);
        const size_t restored = llama_state_seq_set_data(
            g_context, it->kv.data(), it->kv.size(), 0);
        if (restored == 0 || restored > it->kv.size()) {
            LOGw("KV state restore rejected; using full replay fallback");
            snapshot_drop_chat((int64_t)chat_id);
            reset_long_term_states();
            reset_short_term_states();
            return JNI_FALSE;
        }
        chat_msgs = it->messages;
        current_position = it->current_pos;
        system_prompt_position = it->system_pos;
        reset_short_term_states();
        common_sampler_reset(g_sampler);
        // Promote to MRU.
        auto current = std::move(*it);
        g_snapshots.erase(it);
        g_snapshots.push_back(std::move(current));
        LOGi("KV cache hit for chat %lld, %zu bytes, %d tokens",
             (long long)chat_id, restored, (int)current_position);
        return JNI_TRUE;
    } catch (const std::exception &ex) {
        LOGw("KV restore exception: %s", ex.what());
        snapshot_drop_chat((int64_t)chat_id);
        reset_long_term_states();
        reset_short_term_states();
        return JNI_FALSE;
    }
}

// Native schema mode uses the GGUF's LFM3 Jinja chat template. It is optional:
// older GGUFs without the required tool support keep the previous prompt path.
static std::vector<common_chat_tool> g_native_tool_specs;
static bool g_native_tool_mode = false;
static std::chrono::system_clock::time_point g_template_timestamp = std::chrono::system_clock::now();

static std::string render_native_chat(
        const std::vector<common_chat_msg> & messages,
        bool add_generation_prompt) {
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.tools = g_native_tool_specs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = add_generation_prompt;
    // Suppress unnecessary thinking-prefix generation on mobile. Preserve the
    // already-rendered assistant content so incremental token prefixes match.
    inputs.enable_thinking = false;
    inputs.chat_template_kwargs["preserve_thinking"] = "true";
    inputs.now = g_template_timestamp;
    return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt.str();
}

static std::string format_with_native_tools(
        const std::vector<common_chat_msg> & past,
        const common_chat_msg & new_msg, bool add_ass) {
    if (!g_native_tool_mode) {
        return common_chat_format_single(
            g_chat_templates.get(), past, new_msg, add_ass, false);
    }
    const std::string before = past.empty() ? "" : render_native_chat(past, false);
    auto after = past;
    after.push_back(new_msg);
    const std::string rendered = render_native_chat(after, add_ass);
    if (rendered.compare(0, before.size(), before) != 0) {
        throw std::runtime_error("LFM3 Jinja tool prompt is not append-only");
    }
    return rendered.substr(before.size());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureToolsNative(
        JNIEnv *env, jobject, jobjectArray jnames,
        jobjectArray jdescriptions, jobjectArray jschemas) {
    g_native_tool_mode = false;
    g_native_tool_specs.clear();
    if (!g_chat_templates || !g_model) return JNI_FALSE;
    const jsize count = env->GetArrayLength(jnames);
    if (count > 12 || env->GetArrayLength(jdescriptions) != count ||
        env->GetArrayLength(jschemas) != count) return JNI_FALSE;

    // LFM3's template must support both <tools> and <tool_response>.
    // A "chatml" fallback cannot safely expose native schemas.
    const std::string source = common_chat_templates_source(g_chat_templates.get());
    if (source.find("<additional_tools>") == std::string::npos ||
        source.find("<tool_response>") == std::string::npos) {
        LOGw("GGUF lacks native LFM3 tools chat template; using compatibility path");
        return JNI_FALSE;
    }

    std::vector<common_chat_tool> candidates;
    try {
        for (jsize i = 0; i < count; ++i) {
            auto jname = static_cast<jstring>(env->GetObjectArrayElement(jnames, i));
            auto jdesc = static_cast<jstring>(env->GetObjectArrayElement(jdescriptions, i));
            auto jschema = static_cast<jstring>(env->GetObjectArrayElement(jschemas, i));
            if (!jname || !jdesc || !jschema) return JNI_FALSE;
            const char *cn = env->GetStringUTFChars(jname, nullptr);
            const char *cd = env->GetStringUTFChars(jdesc, nullptr);
            const char *cs = env->GetStringUTFChars(jschema, nullptr);
            common_chat_tool tool;
            tool.name = cn;
            tool.description = cd;
            tool.parameters = cs;
            env->ReleaseStringUTFChars(jname, cn);
            env->ReleaseStringUTFChars(jdesc, cd);
            env->ReleaseStringUTFChars(jschema, cs);
            env->DeleteLocalRef(jname);
            env->DeleteLocalRef(jdesc);
            env->DeleteLocalRef(jschema);
            if (tool.name.empty() || tool.name.size() > 64 ||
                tool.description.size() > 300 || tool.parameters.size() > 2048)
                return JNI_FALSE;
            candidates.push_back(std::move(tool));
        }
        g_native_tool_specs = std::move(candidates);
        g_template_timestamp = std::chrono::system_clock::now();

        common_chat_msg system;
        system.role = "system";
        system.content = "You are an offline assistant.";
        common_chat_msg user;
        user.role = "user";
        user.content = "Test";
        const std::string probe = render_native_chat({system, user}, true);
        if (probe.find("<tools>") == std::string::npos ||
            probe.find("<|im_start|>assistant") == std::string::npos) {
            g_native_tool_specs.clear();
            LOGw("Template does not render native tools; falling back to compat mode");
            return JNI_FALSE;
        }
        for (const auto & tool : g_native_tool_specs) {
            if (probe.find(tool.name) == std::string::npos) {
                g_native_tool_specs.clear();
                return JNI_FALSE;
            }
        }
        g_native_tool_mode = true;
        LOGi("LFM3 native tools enabled: %d schemas", (int) g_native_tool_specs.size());
        return JNI_TRUE;
    } catch (const std::exception & ex) {
        LOGw("LFM3 Jinja tools validation failed: %s", ex.what());
        g_native_tool_specs.clear();
        return JNI_FALSE;
    }
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = format_with_native_tools(chat_msgs, new_msg, role == ROLE_USER);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static bool assistant_turn_finalized = false;
static void finalize_assistant_turn() {
    if (!assistant_turn_finalized) {
        const auto reply = assistant_ss.str();
        if (!reply.empty()) {
            common_chat_msg completed;
            completed.role = ROLE_ASSISTANT;
            completed.content = reply;
            chat_msgs.push_back(completed);
        }
        assistant_turn_finalized = true;
    }
}

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    assistant_ss.clear();
    assistant_turn_finalized = false;
}

static int decode_tokens_in_batches(
        llama_context *context,
        common_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Never shift an in-progress batch using stale absolute token positions.
        // The Android coordinator can rebuild recent turns from SQLite instead.
        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGe("%s: Context capacity exhausted before decode", __func__);
            return 2;
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            batch.add(token_id, position, 0, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}


// Rebuilds a named conversation in the current model without reloading the GGUF.
// The chat template supplied by the model defines role boundaries; no string
// concatenation of user-controlled text is used as a substitute for role messages.
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_restoreConversationNative(
        JNIEnv *env, jobject, jstring jsystem, jobjectArray jroles, jobjectArray jcontents) {
    if (!g_model || !g_context || !g_chat_templates || !g_sampler) return 1;
    const jsize count = env->GetArrayLength(jroles);
    if (count != env->GetArrayLength(jcontents) || count > 500) return 2;
    std::vector<common_chat_msg> saved;
    common_chat_msg sys;
    sys.role = ROLE_SYSTEM;
    const char *system_text = env->GetStringUTFChars(jsystem, nullptr);
    sys.content = system_text;
    env->ReleaseStringUTFChars(jsystem, system_text);
    saved.push_back(sys);

    for (jsize n = 0; n < count; ++n) {
        auto role_ref = static_cast<jstring>(env->GetObjectArrayElement(jroles, n));
        auto body_ref = static_cast<jstring>(env->GetObjectArrayElement(jcontents, n));
        if (!role_ref || !body_ref) return 3;
        const char *role = env->GetStringUTFChars(role_ref, nullptr);
        const char *body = env->GetStringUTFChars(body_ref, nullptr);
        common_chat_msg msg;
        msg.role = role;
        msg.content = body;
        env->ReleaseStringUTFChars(role_ref, role);
        env->ReleaseStringUTFChars(body_ref, body);
        env->DeleteLocalRef(role_ref);
        env->DeleteLocalRef(body_ref);
        if (msg.role != ROLE_USER && msg.role != ROLE_ASSISTANT) return 4;
        saved.push_back(std::move(msg));
    }

    try {
        // Keep the latest message pairs inside the 4K context. Reserve at
        // least 768 tokens for the next user prompt and generated answer.
        size_t first = 1;
        llama_tokens tokens;
        std::vector<common_chat_msg> selected;
        while (true) {
            selected.clear();
            selected.push_back(saved.front());
            selected.insert(selected.end(), saved.begin() + first, saved.end());
            std::vector<common_chat_msg> prefix;
            std::string rendered;
            if (g_native_tool_mode) {
                rendered = render_native_chat(selected, false);
            } else {
                for (const auto &msg : selected) {
                    rendered += common_chat_format_single(
                        g_chat_templates.get(), prefix, msg, false, false);
                    prefix.push_back(msg);
                }
            }
            tokens = common_tokenize(g_context, rendered, !g_native_tool_mode, true);
            // Leave room for the next prompt and 512 generated tokens.
            if (tokens.size() <= DEFAULT_CONTEXT_SIZE - 1536) break;
            if (first >= saved.size()) return 5;
            ++first;
            // Drop the matching assistant as well when possible.
            if (first < saved.size() && saved[first].role == ROLE_ASSISTANT) ++first;
        }

        reset_long_term_states();
        reset_short_term_states();
        common_sampler_reset(g_sampler);

        const std::string sys_formatted = g_native_tool_mode
            ? render_native_chat({selected.front()}, false)
            : common_chat_format_single(g_chat_templates.get(), {}, selected.front(), false, false);
        system_prompt_position = (llama_pos) common_tokenize(
            g_context, sys_formatted, !g_native_tool_mode, true).size();
        if (decode_tokens_in_batches(g_context, g_batch, tokens, 0, true)) {
            reset_long_term_states();
            return 6;
        }
        chat_msgs = std::move(selected);
        current_position = (llama_pos) tokens.size();
        LOGi("Restored conversation: %d of %d messages, %d tokens",
             (int) chat_msgs.size() - 1, (int) saved.size() - 1, (int) current_position);
        return 0;
    } catch (const std::exception & e) {
        LOGe("History restoration failed: %s", e.what());
        reset_long_term_states();
        return 7;
    }
}


// A tool result is a real "tool" role in the LFM3 conversation, not a fake
// user instruction. Tool execution happens in the Android host, never in JNI.
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processToolResponse(
        JNIEnv * env, jobject, jstring jname, jstring jresult, jint n_predict) {
    if (!g_model || !g_context || !g_sampler || chat_msgs.empty() ||
            chat_msgs.back().role != ROLE_ASSISTANT) return 1;
    if (n_predict <= 0 || n_predict > 2048) return 2;
    const char * cname = env->GetStringUTFChars(jname, nullptr);
    const char * cresult = env->GetStringUTFChars(jresult, nullptr);
    const std::string name(cname);
    const std::string result(cresult);
    env->ReleaseStringUTFChars(jname, cname);
    env->ReleaseStringUTFChars(jresult, cresult);
    if (name.size() > 64 || result.size() > 1024) return 3;

    common_chat_msg tool;
    tool.role = "tool";
    tool.tool_name = name;
    tool.content = result;

    try {
        const std::string formatted = format_with_native_tools(chat_msgs, tool, true);
        const auto tokens = common_tokenize(g_context, formatted, false, true);
        if (tokens.empty() || tokens.size() > 256) return 4;
        if ((size_t) current_position + tokens.size() + (size_t)n_predict + 8 >=
                DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) return 5;
        reset_short_term_states();
        if (decode_tokens_in_batches(g_context, g_batch, tokens, current_position, true)) return 6;
        chat_msgs.push_back(tool);
        current_position += (llama_pos) tokens.size();
        stop_generation_position = current_position + n_predict;
        LOGi("Tool result appended from %s, %d tokens", name.c_str(), (int) tokens.size());
        return 0;
    } catch (const std::exception & e) {
        LOGe("Failed to append tool result: %s", e.what());
        return 7;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template && !g_native_tool_mode, has_chat_template || g_native_tool_mode);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();
    if (n_predict <= 0 || n_predict > 2048) return 4;

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    common_chat_msg new_user;
    new_user.role = ROLE_USER;
    new_user.content = user_prompt;
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    const std::string formatted_user_prompt = (has_chat_template || g_native_tool_mode)
        ? format_with_native_tools(chat_msgs, new_user, true)
        : new_user.content;
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Do not mutate history before the prompt is checked and decoded.
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt,
                                       has_chat_template && !g_native_tool_mode, has_chat_template || g_native_tool_mode);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    const int user_prompt_size = (int) user_tokens.size();
    // Keep n_predict reserve. Return a recoverable code *without* changing KV
    // or chat_msgs, so Kotlin can reconstruct the latest history and retry.
    if (user_prompt_size <= 0 || user_prompt_size + n_predict + 8 >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGe("%s: User prompt too large for context: %d tokens", __func__, user_prompt_size);
        return 4;
    }
    if (current_position + user_prompt_size + n_predict + 8 >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context capacity reached: pos=%d prompt=%d reply=%d", __func__,
            (int) current_position, user_prompt_size, (int) n_predict);
        return 3;
    }

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    chat_msgs.push_back(std::move(new_user));
    current_position += user_prompt_size;
    stop_generation_position = current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Stop safely at capacity; never silently shift while decoding.
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full, stopping safely for a history rebuild", __func__);
        finalize_assistant_turn();
        return nullptr;
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        finalize_assistant_turn();
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    g_batch.clear();
    g_batch.add(new_token_id, current_position, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        finalize_assistant_turn();
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Partial load errors may leave some native pointers null.
    snapshot_clear_all();
    reset_long_term_states();
    reset_short_term_states();
    if (g_sampler) common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    g_native_tool_specs.clear();
    g_native_tool_mode = false;
    g_batch = common_batch();
    if (g_context) llama_free(g_context);
    g_context = nullptr;
    if (g_model) llama_model_free(g_model);
    g_model = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_finalizePendingTurn(JNIEnv *, jobject) {
    finalize_assistant_turn();
}
