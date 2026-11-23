<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\SearchFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionKind;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Recurr\Rule;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// One run of Maggie at a given time, filed in the user's internal « Maggie »
// agenda (ADR-012, MAG-326): the start of the event is when the agent runs the
// prompt. A proaction is one run, or a series with an RRULE (ADR-012-1, MAG-974):
// each pass of a series becomes an exception instance (recurringEvent and
// originalStartAt) that keeps its own status, answer and error. It never
// reaches Google — its agenda is a module agenda.
//
// Its run status is `executionStatus` and not `status`: the inherited event
// status (confirmed, tentative) keeps that name on the wire.
//
// Read through `/api/proactions` (MAG-328): its own index, so `/api/events`
// never lists it, the way a meal has `/api/meals`. The agenda moves and
// deletes one through `/api/events/{id}`; Maggie reads them through the MCP
// tools.
//
// Comments and not a docblock: see Meal.
#[ORM\Entity(repositoryClass: ProactionRepository::class)]
#[ORM\Index(columns: ['execution_status'], name: 'idx_proaction_execution_status')]
#[ORM\Index(columns: ['topic_key', 'completed_at'], name: 'idx_proaction_topic_key_completed_at')]
#[ApiFilter(SearchFilter::class, properties: ['executionStatus' => 'exact'])]
#[Indexed(index: 'proactions', module: 'calendar')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
])]
class Proaction extends Event implements MercurePublishable
{
    /** The agenda module a proaction is filed in, and the agenda's name when it is created. */
    public const AGENDA_MODULE = Agenda::MODULE_AGENT;
    public const AGENDA_NAME = 'Maggie';
    public const AGENDA_COLOR = '#7C4DFF';

    /** An instant, not a span: the agenda shows it five minutes long. */
    public const DISPLAY_MINUTES = 5;

    public const RRULE_LENGTH = 500;

    /** How long the label read in the agenda may be. */
    private const SUMMARY_LENGTH = 60;

    #[ORM\Column(type: Types::TEXT)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text')]
    #[ApiProperty(writable: false)]
    private string $prompt;

    #[ORM\Column(length: 20, enumType: ProactionStatus::class, options: ['default' => 'pending'])]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(writable: false)]
    private ProactionStatus $executionStatus = ProactionStatus::Pending;

    // A reminder the owner asked for always interrupts; a check speaks only when
    // what it found is worth it (MAG-886).
    #[ORM\Column(length: 10, enumType: ProactionKind::class, options: ['default' => 'reminder'])]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(writable: false)]
    private ProactionKind $kind = ProactionKind::Reminder;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    #[ApiProperty(writable: false)]
    private ?string $response = null;

    // How the completed run reached the owner, the topic it was about and
    // Maggie's verdict on it: what the next check reads back (MAG-886).
    #[ORM\Column(length: 10, nullable: true, enumType: ProactionDelivery::class)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(writable: false)]
    private ?ProactionDelivery $delivery = null;

    #[ORM\Column(length: ProactionOutcome::TOPIC_LENGTH, nullable: true)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(writable: false)]
    private ?string $topicKey = null;

    /** @var array{impact?: string, evidence?: string, deadline?: string, changed?: string}|null */
    #[ORM\Column(type: Types::JSON, nullable: true)]
    #[IndexedField(type: 'object', properties: [
        'impact' => ['type' => 'keyword'],
        'evidence' => ['type' => 'keyword'],
        'deadline' => ['type' => 'date'],
        'changed' => ['type' => 'text'],
    ])]
    #[ApiProperty(writable: false)]
    private ?array $verdict = null;

    // The sentence of the interruption, and the message it opens.
    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    #[ApiProperty(writable: false)]
    private ?string $notice = null;

    #[ORM\Column(length: 120, nullable: true)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(writable: false)]
    private ?string $messageLink = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    #[ApiProperty(writable: false)]
    private ?string $error = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    #[ApiProperty(writable: false)]
    private ?\DateTimeImmutable $completedAt = null;

    /** A short label for the agenda, from the start of the prompt. */
    public static function summaryOf(string $prompt): string
    {
        $flat = trim((string) preg_replace('/\s+/u', ' ', $prompt));
        if (mb_strlen($flat) <= self::SUMMARY_LENGTH) {
            return $flat;
        }

        $cut = mb_substr($flat, 0, self::SUMMARY_LENGTH - 1);
        $space = mb_strrpos($cut, ' ');

        return rtrim(false !== $space && $space > self::SUMMARY_LENGTH / 2 ? mb_substr($cut, 0, $space) : $cut, ' ,.;:').'…';
    }

    #[Ignore]
    public function isAgendaChosenByServer(): bool
    {
        return true;
    }

    /** When Maggie runs it: the start, and the end five minutes later for the agenda. */
    public function scheduleAt(\DateTimeImmutable $at): static
    {
        return $this->scheduleTimed($at, $at);
    }

    public function setStartAt(?\DateTimeImmutable $startAt): static
    {
        // The start is the run; the end only follows it. Whatever door moves a
        // proaction — the agenda's PATCH, `update_event` — the end stays five
        // minutes after.
        //
        // Comments, not a docblock: API Platform publishes a setter's docblock
        // as the description of the property it writes.
        parent::setStartAt($startAt);

        return parent::setEndAt($startAt?->modify(sprintf('+%d minutes', self::DISPLAY_MINUTES)));
    }

    public function setEndAt(?\DateTimeImmutable $endAt): static
    {
        // Derived from the start, see setStartAt().
        return parent::setEndAt($this->getStartAt()?->modify(sprintf('+%d minutes', self::DISPLAY_MINUTES)));
    }

    /** A series repeats at most hourly; an instance of a series takes no rule. */
    #[Assert\Callback]
    public function validateProaction(ExecutionContextInterface $context): void
    {
        $rrule = $this->getRrule();
        if (null !== $rrule) {
            $this->validateSeriesRule($context, $rrule);
        }
        if ($this->isAllDay()) {
            $context->buildViolation('A proaction runs at an instant: it cannot be an all-day event.')->atPath('allDay')->addViolation();
        }
    }

    private function validateSeriesRule(ExecutionContextInterface $context, string $rrule): void
    {
        if ($this->isException()) {
            $context->buildViolation('An occurrence of a series takes no rrule: the rule belongs to the series.')->atPath('rrule')->addViolation();

            return;
        }
        if (strlen($rrule) > self::RRULE_LENGTH || str_starts_with(strtoupper(ltrim($rrule)), 'RRULE:')) {
            $context->buildViolation(sprintf('rrule is an RFC 5545 rule without the RRULE: prefix, at most %d characters.', self::RRULE_LENGTH))->atPath('rrule')->addViolation();

            return;
        }

        try {
            $rule = new Rule($rrule, $this->getStartInstant());
        } catch (\Throwable) {
            $context->buildViolation('rrule is an RFC 5545 rule without the RRULE: prefix, e.g. FREQ=WEEKLY;BYDAY=FR.')->atPath('rrule')->addViolation();

            return;
        }
        if (!in_array($rule->getFreqAsText(), ['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'], true)) {
            $context->buildViolation('A proaction repeats at most hourly: FREQ is HOURLY, DAILY, WEEKLY, MONTHLY or YEARLY.')->atPath('rrule')->addViolation();
        }
    }

    public function getPrompt(): string
    {
        return $this->prompt;
    }

    public function setPrompt(string $prompt): static
    {
        $this->prompt = $prompt;

        return $this;
    }

    public function getExecutionStatus(): ProactionStatus
    {
        return $this->executionStatus;
    }

    public function markRunning(): static
    {
        $this->executionStatus = ProactionStatus::Running;

        return $this;
    }

    public function getKind(): ProactionKind
    {
        return $this->kind;
    }

    public function setKind(ProactionKind $kind): static
    {
        $this->kind = $kind;

        return $this;
    }

    public function markCompleted(ProactionOutcome $outcome, \DateTimeImmutable $at): static
    {
        $this->executionStatus = ProactionStatus::Completed;
        $this->response = $outcome->response;
        $this->delivery = $outcome->delivery;
        $this->topicKey = $outcome->topicKey;
        $this->verdict = $outcome->verdict;
        $this->notice = $outcome->notice;
        $this->messageLink = $outcome->messageLink;
        $this->error = null;
        $this->completedAt = $at;

        return $this;
    }

    public function markFailed(string $error, \DateTimeImmutable $at): static
    {
        $this->executionStatus = ProactionStatus::Failed;
        $this->error = $error;
        $this->response = null;
        $this->completedAt = $at;

        return $this;
    }

    public function getResponse(): ?string
    {
        return $this->response;
    }

    public function getDelivery(): ?ProactionDelivery
    {
        return $this->delivery;
    }

    public function getTopicKey(): ?string
    {
        return $this->topicKey;
    }

    /** @return array{impact?: string, evidence?: string, deadline?: string, changed?: string}|null */
    public function getVerdict(): ?array
    {
        return $this->verdict;
    }

    public function getNotice(): ?string
    {
        return $this->notice;
    }

    public function getMessageLink(): ?string
    {
        return $this->messageLink;
    }

    public function getError(): ?string
    {
        return $this->error;
    }

    public function getCompletedAt(): ?\DateTimeImmutable
    {
        return $this->completedAt;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        $doc = parent::toSearchDocument();
        $doc['prompt'] = $this->prompt;
        $doc['executionStatus'] = $this->executionStatus->value;
        $doc['kind'] = $this->kind->value;
        $doc['response'] = $this->response;
        $doc['delivery'] = $this->delivery?->value;
        $doc['topicKey'] = $this->topicKey;
        $doc['verdict'] = $this->verdict;
        $doc['notice'] = $this->notice;
        $doc['messageLink'] = $this->messageLink;
        $doc['error'] = $this->error;
        $doc['completedAt'] = $this->completedAt?->format('c');

        return $doc;
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'summary' => $this->getSummary(),
            'startAt' => $this->getStartAt()?->format('c'),
            'endAt' => $this->getEndAt()?->format('c'),
            'status' => $this->getStatus()->value,
            'executionStatus' => $this->executionStatus->value,
            'kind' => $this->kind->value,
            'delivery' => $this->delivery?->value,
            'completedAt' => $this->completedAt?->format('c'),
        ], $changedProperties);
    }
}
