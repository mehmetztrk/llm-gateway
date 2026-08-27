package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionRequestDto;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionResponseDto;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.MessageDto;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.Role;
import java.util.List;

/**
 * Translates between the OpenAI wire format and the gateway's domain model.
 *
 * <p>This is the only class that knows both vocabularies. Keeping the translation in one place
 * means a future OpenAI schema change has exactly one blast radius.
 */
final class OpenAiMapper {

    private OpenAiMapper() {}

    static ChatRequest toDomain(ChatCompletionRequestDto dto) {
        List<ChatMessage> messages = dto.messages().stream()
                .map(m -> new ChatMessage(Role.fromWire(m.role()), m.content()))
                .toList();
        return new ChatRequest(dto.model(), messages, dto.maxTokens(), dto.temperature());
    }

    static ChatCompletionResponseDto toWire(Completion completion) {
        return new ChatCompletionResponseDto(
                completion.id(),
                ChatCompletionResponseDto.OBJECT_TYPE,
                completion.createdAt().getEpochSecond(),
                completion.model(),
                List.of(new ChatCompletionResponseDto.ChoiceDto(
                        0,
                        new MessageDto(
                                completion.message().role().wireValue(),
                                completion.message().content()),
                        completion.finishReason().wireValue())),
                new ChatCompletionResponseDto.UsageDto(
                        completion.usage().promptTokens(),
                        completion.usage().completionTokens(),
                        completion.usage().totalTokens()));
    }
}
