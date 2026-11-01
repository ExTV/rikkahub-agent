#include <jni.h>
#include <algorithm>
#include <atomic>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "jni_util.h"
#include "chat.h"
#include "sampling.h"
#include <nlohmann/json.hpp>

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeSystemInfo(JNIEnv *env, jobject) {
    JNI_GUARD(env, nullptr, {
        llama_backend_init();
        const std::string info = llama_print_system_info();
        return env->NewStringUTF(info.c_str());
    })
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeLoadModel(JNIEnv *env, jobject, jstring pathIn) {
    JNI_GUARD(env, 0L, {
        llama_backend_init();
        const std::string path = jstringToUtf8(env, pathIn);

        llama_model_params params = llama_model_default_params();
        // CPU only for now; the spec keeps GPU offload behind a CMake option.
        params.n_gpu_layers = 0;
        // Default params already select mmap; set it explicitly since the field this
        // used to be (a plain use_mmap bool) was replaced by this enum in b10228.
        params.load_mode = LLAMA_LOAD_MODE_MMAP;

        llama_model *model = llama_model_load_from_file(path.c_str(), params);
        if (model == nullptr) {
            throwJava(env, ("failed to load model: " + path).c_str());
            return 0L;
        }
        return reinterpret_cast<jlong>(model);
    })
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeFreeModel(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD_VOID(env, {
        if (handle == 0L) {
            return;
        }
        llama_model_free(reinterpret_cast<llama_model *>(handle));
    })
}

// Reads one integer-valued metadata key, trying the architecture-prefixed name first.
// Only used for the head dimensions: llama.cpp exposes typed accessors for everything
// else this file needs (head_count_kv, head_count, sliding window).
static int metaInt(const llama_model *model, const std::string &suffix, int fallback) {
    char arch[128] = {0};
    llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));

    char buf[128] = {0};
    const std::string key = std::string(arch) + "." + suffix;
    if (llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf)) > 0) {
        try {
            return std::stoi(buf);
        } catch (...) {
            return fallback;
        }
    }
    return fallback;
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD(env, nullptr, {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (model == nullptr) {
            throwJava(env, "model handle is null");
            return nullptr;
        }

        const int nEmbd     = llama_model_n_embd(model);
        const int nLayers   = llama_model_n_layer(model);
        const int nCtxTrain = llama_model_n_ctx_train(model);
        const int nHeadKv   = llama_model_n_head_kv(model);
        const int nHeadCnt  = llama_model_n_head(model);
        const int slidingWindow = llama_model_n_swa(model);

        // Head dims are declared on newer models and derivable on older ones. There is no
        // typed accessor for them, so read the metadata key directly. The nEmbd / nHeadCnt
        // fallback only holds without grouped-query attention (nHeadKv == nHeadCnt); with
        // GQA, such as Qwen3, Gemma 3, and several Llama derivatives, that ratio is simply
        // wrong. Leave a missing value as 0 there so isComplete catches it, rather than
        // handing the planner a plausible-looking but incorrect head dimension.
        int headK = metaInt(model, "attention.key_length", 0);
        int headV = metaInt(model, "attention.value_length", 0);
        if (headK == 0 && nHeadCnt > 0 && nHeadKv == nHeadCnt) {
            headK = nEmbd / nHeadCnt;
        }
        if (headV == 0 && nHeadCnt > 0 && nHeadKv == nHeadCnt) {
            headV = nEmbd / nHeadCnt;
        }

        const llama_vocab *vocab = llama_model_get_vocab(model);

        std::ostringstream out;
        out << "{"
            << "\"n_layers\":"       << nLayers                        << ","
            << "\"n_embd\":"         << nEmbd                          << ","
            << "\"n_head_kv\":"      << nHeadKv                        << ","
            << "\"n_embd_head_k\":"  << headK                          << ","
            << "\"n_embd_head_v\":"  << headV                          << ","
            << "\"n_vocab\":"        << llama_vocab_n_tokens(vocab)    << ","
            << "\"n_ctx_train\":"    << nCtxTrain                      << ","
            << "\"sliding_window\":" << slidingWindow                  << ","
            << "\"weights_bytes\":"  << llama_model_size(model)
            << "}";
        return env->NewStringUTF(out.str().c_str());
    })
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeChatTemplate(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD(env, nullptr, {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (model == nullptr) {
            throwJava(env, "model handle is null");
            return nullptr;
        }
        const char *tmpl = llama_model_chat_template(model, nullptr);
        return tmpl == nullptr ? nullptr : env->NewStringUTF(tmpl);
    })
}

// Emits the trigger type by name rather than the enum's positional ordinal
// (common_grammar_trigger_type has no explicit values), so a llama.cpp bump that inserts a new
// variant produces a visible lookup miss on the Kotlin side instead of silently relabelling
// every existing trigger.
static const char *grammarTriggerTypeName(common_grammar_trigger_type type) {
    switch (type) {
        case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:        return "token";
        case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:         return "word";
        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:      return "pattern";
        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL: return "pattern_full";
    }
    return "unknown";
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeApplyTemplate(
        JNIEnv *env, jobject, jlong handle, jbyteArray requestIn) {
    JNI_GUARD(env, nullptr, {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (model == nullptr) {
            throwJava(env, "model handle is null");
            return nullptr;
        }

        // Carried as a byte[], not a jstring: GetStringUTFChars would hand back Modified UTF-8
        // (see jni_util.h), which nlohmann's strict UTF-8 parser rejects for any request
        // containing a supplementary-plane character, e.g. an emoji in a chat message.
        const std::string requestStr = byteArrayToUtf8(env, requestIn);
        const nlohmann::ordered_json request = nlohmann::ordered_json::parse(requestStr);

        // Reads the Jinja template out of the GGUF itself. Passing "" does not fail on a
        // model with no stored template: common_chat_templates_init (common/chat.cpp) falls
        // back to a built-in ChatML template in that case rather than throwing. It only
        // throws if a template (fallback or the model's own) fails to parse, and that is
        // already turned into a Java exception by JNI_GUARD below.
        common_chat_templates_ptr tmpls = common_chat_templates_init(model, "");

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(request.at("messages"));
        if (request.contains("tools") && !request.at("tools").is_null()) {
            inputs.tools = common_chat_tools_parse_oaicompat(request.at("tools"));
        }
        // Left at its default of "auto" the tool-call grammar permits zero calls, so the model
        // may always decline to call anything: chat.cpp derives both `min_calls` and whether
        // the grammar is lazy from this. "required" is the only way to make a tool call
        // actually obligatory.
        if (request.contains("tool_choice") && !request.at("tool_choice").is_null()) {
            inputs.tool_choice =
                    common_chat_tool_choice_parse_oaicompat(request.at("tool_choice").get<std::string>());
        }
        // Defaults to true, so a thinking model reasons unless the caller says otherwise. Set
        // false and the template writes an empty thinking block into the prompt itself, which
        // is how a caller skips straight to the answer.
        if (request.contains("enable_thinking") && !request.at("enable_thinking").is_null()) {
            inputs.enable_thinking = request.at("enable_thinking").get<bool>();
        }
        inputs.add_generation_prompt = true;
        // use_jinja and enable_thinking keep their struct defaults (both true): use_jinja
        // is what makes tool declarations and thinking reach the template at all, and
        // enable_thinking matches what ChatDeltaTracker expects downstream, that the model
        // emits its own thinking block for reasoning_content to be split out of.
        //
        // Whether that thinking block is ever actually split out is decided here rather than
        // at parse time. common_chat_templates_apply evaluates
        // `inputs.reasoning_format != COMMON_REASONING_FORMAT_NONE` while it builds the parser
        // and bakes the answer into it (common/chat.cpp). The struct default is NONE, so left
        // alone the parser is built to leave thinking inline, reasoning_content is always
        // empty, and ChatDeltaTracker's reasoning channel is permanently dead. DEEPSEEK is
        // what llama.cpp's own server defaults to and is the variant that reports reasoning
        // separately in streaming deltas rather than inlining it.
        inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;

        const common_chat_params params = common_chat_templates_apply(tmpls.get(), inputs);

        nlohmann::ordered_json out;
        out["prompt"]             = params.prompt;
        out["grammar"]            = params.grammar;
        out["grammar_lazy"]       = params.grammar_lazy;
        out["additional_stops"]   = params.additional_stops;
        out["preserved_tokens"]   = params.preserved_tokens;
        out["thinking_start_tag"] = params.thinking_start_tag;
        out["thinking_end_tags"]  = params.thinking_end_tags;
        out["format"]             = common_chat_format_name(params.format);
        out["generation_prompt"]  = params.generation_prompt;

        // The parser the template layer built for this exact request, serialized. This is the
        // whole of what reading the response back needs: common_chat_parse does NOT dispatch on
        // "format" above, it parses with common_chat_parser_params::parser and quietly
        // substitutes a content-only parser when that is empty, which returns plausible text
        // with every tool call missing. "format" is still carried because the parse side uses
        // it to pick a mapper for two of the formats, but it is not a substitute for this.
        // The value is itself a JSON document (common_peg_arena::save dumps one), nested here
        // as a string exactly as llama.cpp's server carries it between the two halves.
        out["parser"]             = params.parser;

        // trigger.token (for a COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN trigger) is not carried across:
        // every construction site in common/chat.cpp builds a "word" or "pattern" trigger today,
        // so nothing is lost, but a future "token" trigger would serialize with an empty value
        // and never fire.
        nlohmann::ordered_json triggers = nlohmann::ordered_json::array();
        for (const auto &trigger : params.grammar_triggers) {
            triggers.push_back({
                {"type",  grammarTriggerTypeName(trigger.type)},
                {"value", trigger.value},
            });
        }
        out["grammar_triggers"] = triggers;

        // Returned as a byte[], not a jstring: out.dump() is standard UTF-8, and NewStringUTF
        // requires Modified UTF-8, which does not allow the four-byte sequences a
        // supplementary-plane character in the rendered prompt would produce.
        return utf8ToByteArray(env, out.dump());
    })
}

// ---------------------------------------------------------------------------
// Context lifecycle
// ---------------------------------------------------------------------------

static ggml_type cacheTypeFromId(const std::string &id) {
    if (id == "f16")  return GGML_TYPE_F16;
    if (id == "q8_0") return GGML_TYPE_Q8_0;
    // Deliberately not a silent fallback to f16. ContextPlanner sizes the context for the
    // cache type it picked, so substituting a different one hands back a context that does
    // not fit the budget the planner just proved it would fit.
    throw std::invalid_argument("unknown kv cache type: " + id);
}

// A llama_context together with the flag its abort callback reads.
//
// The flag cannot live on the generate stack. Cancelling is only interesting during a long
// prefill, and for the whole of that the generating thread is inside llama_decode and cannot
// poll anything itself, so the flag has to be writable from another thread while generation
// runs. Tying it to the context also settles the lifetime question: the callback's data
// pointer stays valid for exactly as long as the context it was installed on, because the
// same destructor drops both.
//
// Nothing here makes concurrent generation safe. llama_context is not thread-safe and the
// runtime serialises generation per loaded model; the only call that may cross threads is
// nativeCancelGeneration, which touches nothing but the atomic.
struct GenerationContext {
    llama_context    *ctx = nullptr;
    std::atomic<bool> cancelled{false};

    ~GenerationContext() {
        if (ctx != nullptr) {
            llama_free(ctx);
        }
    }
};

static bool abortIfCancelled(void *data) {
    auto *gen = static_cast<GenerationContext *>(data);
    return gen != nullptr && gen->cancelled.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeCreateContext(
        JNIEnv *env, jobject, jlong modelHandle, jint nCtx, jint nBatch, jint nUBatch,
        jstring cacheKIn, jstring cacheVIn, jint nThreads) {
    JNI_GUARD(env, 0L, {
        auto *model = reinterpret_cast<llama_model *>(modelHandle);
        if (model == nullptr) {
            throwJava(env, "model handle is null");
            return 0L;
        }
        if (nCtx <= 0 || nBatch <= 0 || nUBatch <= 0 || nThreads <= 0) {
            throwJava(env, "context, batch and thread sizes must all be positive");
            return 0L;
        }

        llama_context_params params = llama_context_default_params();
        params.n_ctx           = static_cast<uint32_t>(nCtx);
        params.n_batch         = static_cast<uint32_t>(nBatch);
        params.n_ubatch        = static_cast<uint32_t>(nUBatch);
        params.n_threads       = nThreads;
        params.n_threads_batch = nThreads;
        params.type_k          = cacheTypeFromId(jstringToUtf8(env, cacheKIn));
        params.type_v          = cacheTypeFromId(jstringToUtf8(env, cacheVIn));

        auto gen = std::make_unique<GenerationContext>();
        gen->ctx = llama_init_from_model(model, params);
        if (gen->ctx == nullptr) {
            throwJava(env, "failed to create context; the planned size may not fit memory");
            return 0L;
        }
        // Installed once for the context's whole life rather than around each generation:
        // there is then no window in which the callback holds a pointer to something already
        // gone, and no set/clear pair for a throwing path to leave half done.
        llama_set_abort_callback(gen->ctx, abortIfCancelled, gen.get());
        return reinterpret_cast<jlong>(gen.release());
    })
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeFreeContext(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD_VOID(env, {
        if (handle == 0L) {
            return;
        }
        delete reinterpret_cast<GenerationContext *>(handle);
    })
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeCancelGeneration(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD_VOID(env, {
        if (handle == 0L) {
            return;
        }
        reinterpret_cast<GenerationContext *>(handle)->cancelled.store(
                true, std::memory_order_relaxed);
    })
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

// llama_token_to_piece writes into a caller-supplied buffer and returns the negated length it
// needed when the buffer was too small. Most pieces are a few bytes, but nothing in the API
// caps them, so the too-small answer has to be handled rather than assumed away.
static void appendPiece(const llama_vocab *vocab, llama_token id, std::string &out) {
    char buf[128];
    int32_t written = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, /*special*/ false);
    if (written >= 0) {
        out.append(buf, static_cast<size_t>(written));
        return;
    }
    std::vector<char> larger(static_cast<size_t>(-written));
    written = llama_token_to_piece(vocab, id, larger.data(),
                                   static_cast<int32_t>(larger.size()), 0, /*special*/ false);
    if (written < 0) {
        throw std::runtime_error("failed to render a token to text");
    }
    out.append(larger.data(), static_cast<size_t>(written));
}

// How many trailing bytes start a UTF-8 sequence that is not finished yet and so must be held
// back rather than handed over.
//
// A byte-level BPE vocabulary splits any character it has no whole token for into one token
// per byte, so a single generated token routinely carries half a character. Handing that half
// to Kotlin is not a transient glitch that the next piece repairs: each piece is decoded into
// its own String, so the half becomes a permanent replacement character and the other half
// becomes a second one.
static size_t incompleteUtf8Tail(const std::string &text) {
    const size_t size = text.size();
    for (size_t back = 1; back <= 4 && back <= size; ++back) {
        const auto lead = static_cast<unsigned char>(text[size - back]);
        if ((lead & 0xC0) == 0x80) {
            continue;  // a continuation byte: the sequence starts further back
        }
        size_t needed = 1;
        if ((lead & 0xE0) == 0xC0) {
            needed = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            needed = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            needed = 4;
        }
        // Fewer bytes present than the lead byte promises means unfinished. Everything else,
        // including a byte that is not a valid lead at all, passes straight through: holding
        // malformed bytes back forever would lose them outright, which is worse than showing
        // them once as a replacement character.
        return back < needed ? back : 0;
    }
    return 0;
}

// The inverse of grammarTriggerTypeName above. A switch is right here, unlike for the chat
// format names, because these strings are this file's own invention rather than llama.cpp's.
static common_grammar_trigger_type grammarTriggerTypeFromName(const std::string &name) {
    if (name == "token")        return COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
    if (name == "word")         return COMMON_GRAMMAR_TRIGGER_TYPE_WORD;
    if (name == "pattern")      return COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN;
    if (name == "pattern_full") return COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL;
    throw std::invalid_argument("unknown grammar trigger type: " + name);
}

// Rebuilds sampling parameters from what nativeApplyTemplate produced, mirroring what
// llama.cpp's own server does with the same fields (tools/server/server-schema.cpp).
static common_params_sampling samplingFromApplied(const nlohmann::ordered_json &applied,
                                                  const llama_vocab            *vocab) {
    common_params_sampling sparams;

    const std::string grammar = applied.value("grammar", std::string());
    if (!grammar.empty()) {
        // Typed as a tool-call grammar because that is the only kind nativeApplyTemplate can
        // produce: it passes neither a json_schema nor a user grammar, so anything here came
        // from the template's tool declarations. The type is not decoration; it is what
        // decides whether the grammar sampler is advanced past the generation prompt already
        // sitting in the prompt (common/sampling.cpp).
        //
        // The empty case must leave the default-constructed value alone rather than build one:
        // common_grammar's two-argument constructor asserts on (type NONE, empty string), and
        // a GGML_ASSERT aborts the process rather than throwing something catchable.
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, grammar);
    }
    sparams.grammar_lazy      = applied.value("grammar_lazy", false);
    sparams.generation_prompt = applied.value("generation_prompt", std::string());

    // Preserved tokens are resolved first: the trigger conversion below refuses a single-token
    // trigger word that is not in this set, exactly as the server does.
    if (applied.contains("preserved_tokens")) {
        for (const auto &entry : applied.at("preserved_tokens")) {
            const auto ids = common_tokenize(vocab, entry.get<std::string>(), false, true);
            if (ids.size() == 1) {
                sparams.preserved_tokens.insert(ids[0]);
            }
        }
    }

    if (applied.contains("grammar_triggers")) {
        for (const auto &entry : applied.at("grammar_triggers")) {
            common_grammar_trigger trigger;
            trigger.type  = grammarTriggerTypeFromName(entry.at("type").get<std::string>());
            trigger.value = entry.at("value").get<std::string>();
            if (trigger.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) {
                const auto ids = common_tokenize(vocab, trigger.value, false, true);
                if (ids.size() == 1) {
                    // A trigger word that is exactly one token is matched by id instead, which
                    // is both cheaper and immune to the word tokenizing differently in
                    // context. This also fills in the trigger.token that the apply side cannot
                    // carry across, since it has no vocabulary to resolve it against.
                    if (sparams.preserved_tokens.count(ids[0]) == 0) {
                        throw std::runtime_error(
                                "grammar trigger word is not a preserved token: " + trigger.value);
                    }
                    trigger.type  = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    trigger.token = ids[0];
                }
            }
            sparams.grammar_triggers.push_back(std::move(trigger));
        }
    }

    if (sparams.grammar_lazy && sparams.grammar_triggers.empty()) {
        // A lazy grammar with no trigger never activates, so tool calling would run entirely
        // unconstrained while every visible sign said a grammar was in force. Fail loudly.
        throw std::runtime_error("lazy grammar has no triggers, so it would never activate");
    }
    return sparams;
}

// Declared out here rather than inline in the body below: the comma in the template argument
// list is not inside parentheses, and JNI_GUARD_VOID is a macro, so it would be read as an
// extra macro argument.
using SamplerPtr = std::unique_ptr<common_sampler, void (*)(common_sampler *)>;

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeGenerate(
        JNIEnv *env, jobject, jlong ctxHandle, jlong modelHandle, jbyteArray appliedIn,
        jint maxTokens, jobject sink) {
    JNI_GUARD_VOID(env, {
        auto *gen   = reinterpret_cast<GenerationContext *>(ctxHandle);
        auto *model = reinterpret_cast<llama_model *>(modelHandle);
        if (gen == nullptr || gen->ctx == nullptr || model == nullptr) {
            throwJava(env, "context or model handle is null");
            return;
        }
        if (sink == nullptr) {
            throwJava(env, "token sink is null");
            return;
        }
        if (maxTokens <= 0) {
            throwJava(env, "maxTokens must be positive");
            return;
        }

        llama_context     *ctx   = gen->ctx;
        const llama_vocab *vocab = llama_model_get_vocab(model);

        jclass    sinkCls = env->GetObjectClass(sink);
        jmethodID onToken = env->GetMethodID(sinkCls, "onToken", "([B)Z");
        env->DeleteLocalRef(sinkCls);
        if (onToken == nullptr) {
            // GetMethodID has already raised NoSuchMethodError. Throwing a second exception on
            // top of a pending one is not allowed, so just unwind.
            return;
        }

        const nlohmann::ordered_json applied =
                nlohmann::ordered_json::parse(byteArrayToUtf8(env, appliedIn));
        const std::string prompt = applied.at("prompt").get<std::string>();

        // Each generation starts uncancelled: a cancel that arrived while nothing was running
        // must not silently kill the next one.
        gen->cancelled.store(false, std::memory_order_relaxed);

        std::vector<llama_token> tokens(prompt.size() + 64);
        int32_t nPrompt = llama_tokenize(
                vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                tokens.data(), static_cast<int32_t>(tokens.size()),
                /*add_special*/ true, /*parse_special*/ true);
        if (nPrompt < 0) {
            tokens.resize(static_cast<size_t>(-nPrompt));
            nPrompt = llama_tokenize(
                    vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                    tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
        }
        if (nPrompt <= 0) {
            throwJava(env, "prompt tokenization failed");
            return;
        }
        tokens.resize(static_cast<size_t>(nPrompt));

        // Built before the prefill, not after, so an unusable grammar costs nothing: the check
        // that a lazy grammar has triggers is cheap and the prefill it would otherwise follow
        // is the single most expensive thing here.
        common_params_sampling sparams = samplingFromApplied(applied, vocab);
        // common_sampler_init takes its params by non-const reference and may adjust them, so
        // sparams above is a mutable local rather than a temporary. unique_ptr owns the result
        // from here on: every exit below, including the throwing ones and a Kotlin sink that
        // throws, runs the deleter.
        SamplerPtr smpl(common_sampler_init(model, sparams), &common_sampler_free);
        if (!smpl) {
            throwJava(env, "failed to initialise the sampler");
            return;
        }

        // Each generation starts from an empty cache. llama_batch_get_one continues from
        // wherever the previous decode on this context left off, so without this the second
        // call's prompt would be appended to the first call's conversation rather than
        // replacing it. The prompt already carries the whole history, so that would both
        // duplicate every earlier turn and run the context out within a few turns. Reusing the
        // shared prefix instead of clearing it is a worthwhile optimisation and a separate
        // piece of work; it is not what happens by default.
        llama_memory_clear(llama_get_memory(ctx), /*data*/ true);

        // Prefill in n_batch-sized slices. Handing llama_decode more tokens than n_batch is not
        // an error return, it is GGML_ASSERT(n_tokens_all <= cparams.n_batch) in
        // llama_context::decode, which aborts the process; and any real chat prompt carrying a
        // system prompt and tool schemas is longer than the planner's batch. llama_batch_get_one
        // tracks positions itself, so consecutive calls continue the same sequence, and with a
        // null logits array only the final token of each slice is an output.
        const int32_t batchSize = static_cast<int32_t>(llama_n_batch(ctx));
        for (int32_t start = 0; start < nPrompt; start += batchSize) {
            // Checked before the first decode as well as between slices, so a cancel that
            // lands in the window between tokenizing and decoding is honoured instead of
            // being read only after a full prefill has already run.
            if (gen->cancelled.load(std::memory_order_relaxed)) {
                return;
            }
            const int32_t count = std::min(batchSize, nPrompt - start);
            const int32_t rc = llama_decode(ctx, llama_batch_get_one(tokens.data() + start, count));
            if (rc != 0) {
                // A cancel that lands mid-decode comes back as rc 2 from the abort callback.
                // That is the caller getting what it asked for, not a failure.
                if (gen->cancelled.load(std::memory_order_relaxed)) {
                    return;
                }
                if (rc == 1) {
                    // The token count is worth saying: the caller planned the context from the
                    // model's metadata and a byte estimate of the prompt, so the useful
                    // question when this fires is by how much that estimate was off.
                    throwJava(env, ("prompt does not fit the context: " +
                                    std::to_string(nPrompt) + " tokens").c_str());
                } else {
                    throwJava(env, "failed to evaluate the prompt");
                }
                return;
            }
        }

        std::string pending;  // bytes held back because they are mid-character
        bool        stopped = false;

        for (jint i = 0; i < maxTokens; i++) {
            const llama_token id = common_sampler_sample(smpl.get(), ctx, -1);
            if (llama_vocab_is_eog(vocab, id)) {
                break;
            }
            common_sampler_accept(smpl.get(), id, /*is_generated*/ true);

            appendPiece(vocab, id, pending);
            const size_t hold = incompleteUtf8Tail(pending);
            if (pending.size() > hold) {
                jbyteArray piece = utf8ToByteArray(env, pending.substr(0, pending.size() - hold));
                pending.erase(0, pending.size() - hold);
                if (piece == nullptr) {
                    throwJava(env, "failed to allocate a token buffer");
                    return;
                }
                const jboolean keepGoing = env->CallBooleanMethod(sink, onToken, piece);
                env->DeleteLocalRef(piece);
                if (env->ExceptionCheck()) {
                    // The sink threw. Its exception is the one the caller should see and no
                    // further JNI call is legal while one is pending, so leave it there and
                    // unwind; the sampler is still freed on the way out.
                    return;
                }
                if (keepGoing == JNI_FALSE) {
                    stopped = true;
                    break;
                }
            }

            if (gen->cancelled.load(std::memory_order_relaxed)) {
                stopped = true;
                break;
            }
            if (i + 1 >= maxTokens) {
                // Nothing would ever be sampled from this decode, and it is the one most likely
                // to be the token that exhausts the KV cache. Skip it.
                break;
            }

            llama_token next = id;
            const int32_t rc = llama_decode(ctx, llama_batch_get_one(&next, 1));
            if (rc != 0) {
                if (gen->cancelled.load(std::memory_order_relaxed)) {
                    stopped = true;
                    break;
                }
                throwJava(env, rc == 1
                        ? "ran out of context while generating"
                        : "failed to evaluate a generated token");
                return;
            }
        }

        // Bytes can only be left here if generation ended mid-character, which hitting
        // maxTokens can do. They are handed over rather than dropped: an incomplete character
        // surfaces as a visible replacement character, whereas dropping it loses text with no
        // sign that anything went missing. Not flushed after a stop, since the caller has
        // already said it wants nothing more.
        if (!stopped && !pending.empty()) {
            jbyteArray tail = utf8ToByteArray(env, pending);
            if (tail != nullptr) {
                env->CallBooleanMethod(sink, onToken, tail);
                env->DeleteLocalRef(tail);
            }
        }
    })
}

// The reverse of common_chat_format_name, derived from that function rather than hand-written,
// so a llama.cpp bump that renames or reorders a format produces a visible miss here instead of
// a switch that still compiles and now maps the wrong way. COMMON_CHAT_FORMAT_COUNT is the
// enum's own sentinel (common/chat.h).
static common_chat_format formatFromName(const std::string &name) {
    for (int i = 0; i < COMMON_CHAT_FORMAT_COUNT; ++i) {
        const auto format = static_cast<common_chat_format>(i);
        if (name == common_chat_format_name(format)) {
            return format;
        }
    }
    throw std::invalid_argument("unknown chat format: " + name);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeParseChat(
        JNIEnv *env, jobject, jbyteArray textIn, jboolean isPartial, jbyteArray appliedIn) {
    JNI_GUARD(env, nullptr, {
        // Both directions are bytes for the same reason nativeApplyTemplate's are: this is
        // model-generated text going in and text plus tool arguments coming back, and a jstring
        // would put Modified UTF-8 on a boundary that standard UTF-8 has to cross.
        const std::string            text    = byteArrayToUtf8(env, textIn);
        const nlohmann::ordered_json applied =
                nlohmann::ordered_json::parse(byteArrayToUtf8(env, appliedIn));

        common_chat_parser_params params;
        params.format            = formatFromName(applied.at("format").get<std::string>());
        // Prepended to the text before parsing, because most of the parsers the template layer
        // builds open by matching the generation prompt as a literal.
        params.generation_prompt = applied.value("generation_prompt", std::string());

        const std::string parser = applied.value("parser", std::string());
        if (parser.empty()) {
            // common_chat_parse substitutes a content-only parser for an empty one instead of
            // failing, so an applied-template blob that lost this field would keep returning
            // believable text with every tool call quietly gone. Refuse it here instead.
            throw std::runtime_error("applied template carries no parser");
        }
        params.parser.load(parser);

        const common_chat_msg msg = common_chat_parse(text, isPartial == JNI_TRUE, params);
        return utf8ToByteArray(env, msg.to_json_oaicompat().dump());
    })
}
