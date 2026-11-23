<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionEvidence;
use Maggie\Calendar\Enum\ProactionImpact;

/**
 * What a completed run gave (MAG-886): Maggie's answer, how it reached the owner,
 * the topic it was about and her verdict on it — the trace the next check reads
 * back so it does not say the same thing twice.
 */
final readonly class ProactionOutcome
{
    public const TOPIC_LENGTH = 80;
    private const TOPIC = '/^[a-z0-9:-]{1,80}$/';
    private const MESSAGE_LINK = '/^\/(chat|digest)\?message=[0-9A-Za-z-]{1,64}$/';
    private const INSTANT = '/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/';
    private const CHANGED_LENGTH = 500;
    public const VALUE_LENGTH = 120;

    /**
     * @param array{impact?: string, evidence?: string, deadline?: string, changed?: string, value?: string}|null $verdict
     */
    public function __construct(
        public ?string $response = null,
        public ?ProactionDelivery $delivery = null,
        public ?string $topicKey = null,
        public ?array $verdict = null,
        public ?string $notice = null,
        public ?string $messageLink = null,
    ) {
    }

    /**
     * From what the executor sends, every value checked.
     *
     * @throws \DomainException on a value outside the contract
     */
    public static function fromInput(
        ?string $response,
        ?string $delivery = null,
        ?string $topicKey = null,
        ?string $impact = null,
        ?string $evidence = null,
        ?string $deadline = null,
        ?string $changed = null,
        ?string $notice = null,
        ?string $messageLink = null,
        ?string $value = null,
    ): self {
        $delivery = self::blankToNull($delivery);
        $topicKey = self::blankToNull($topicKey);
        $impact = self::blankToNull($impact);
        $evidence = self::blankToNull($evidence);
        $deadline = self::blankToNull($deadline);
        $changed = self::blankToNull($changed);
        $value = self::blankToNull($value);
        $messageLink = self::blankToNull($messageLink);

        if (null !== $topicKey && 1 !== preg_match(self::TOPIC, $topicKey)) {
            throw new \DomainException(sprintf('Invalid topic_key "%s": lowercase letters, digits, ":" and "-", %d characters at most.', $topicKey, self::TOPIC_LENGTH));
        }
        if (null !== $messageLink && 1 !== preg_match(self::MESSAGE_LINK, $messageLink)) {
            throw new \DomainException(sprintf('Invalid message_link "%s": /chat?message=<id> or /digest?message=<id>.', $messageLink));
        }

        $verdict = array_filter([
            'impact' => null !== $impact ? (ProactionImpact::tryFrom($impact) ?? throw new \DomainException(sprintf('Invalid impact "%s": use low, medium or high.', $impact)))->value : null,
            'evidence' => null !== $evidence ? (ProactionEvidence::tryFrom($evidence) ?? throw new \DomainException(sprintf('Invalid evidence "%s": use weak or solid.', $evidence)))->value : null,
            'deadline' => null !== $deadline ? self::instant($deadline)->format('c') : null,
            'changed' => null !== $changed ? mb_substr($changed, 0, self::CHANGED_LENGTH) : null,
            'value' => null !== $value ? mb_substr($value, 0, self::VALUE_LENGTH) : null,
        ], static fn (?string $value): bool => null !== $value);

        return new self(
            response: $response,
            delivery: null !== $delivery
                ? (ProactionDelivery::tryFrom($delivery) ?? throw new \DomainException(sprintf('Invalid delivery "%s": use interrupt, thread or none.', $delivery)))
                : null,
            topicKey: $topicKey,
            verdict: [] !== $verdict ? $verdict : null,
            notice: self::blankToNull($notice),
            messageLink: $messageLink,
        );
    }

    private static function instant(string $value): \DateTimeImmutable
    {
        if (1 !== preg_match(self::INSTANT, $value)) {
            throw new \DomainException(sprintf('Invalid deadline "%s": ISO 8601, e.g. 2037-01-15T08:30:00+01:00.', $value));
        }

        try {
            $at = new \DateTimeImmutable($value, new \DateTimeZone(Proaction::FALLBACK_TIME_ZONE));
        } catch (\Exception $e) {
            throw new \DomainException(sprintf('Invalid deadline "%s": ISO 8601, e.g. 2037-01-15T08:30:00+01:00.', $value), 0, $e);
        }

        if ($at->format('Y-m-d') !== substr($value, 0, 10)) {
            throw new \DomainException(sprintf('Invalid deadline "%s": no such day.', $value));
        }

        return $at;
    }

    private static function blankToNull(?string $value): ?string
    {
        return null !== $value && '' !== trim($value) ? trim($value) : null;
    }
}
