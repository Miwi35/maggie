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
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// One run of Maggie at a given time, filed in the user's internal « Maggie »
// agenda (ADR-012, MAG-326): the start of the event is when the agent runs the
// prompt. The lasting rule lives in the agent's instructions; a proaction is
// one occurrence of it, so it never repeats (no RRULE) and never reaches
// Google — its agenda is a module agenda.
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

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    #[ApiProperty(writable: false)]
    private ?string $response = null;

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

    /** A proaction never repeats: the repetition is the agent's instruction (ADR-012). */
    #[Assert\Callback]
    public function validateProaction(ExecutionContextInterface $context): void
    {
        if (null !== $this->getRrule()) {
            $context->buildViolation('A proaction never repeats: a lasting rule is an instruction for Maggie, not an RRULE.')->atPath('rrule')->addViolation();
        }
        if ($this->isAllDay()) {
            $context->buildViolation('A proaction runs at an instant: it cannot be an all-day event.')->atPath('allDay')->addViolation();
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

    public function markCompleted(?string $response, \DateTimeImmutable $at): static
    {
        $this->executionStatus = ProactionStatus::Completed;
        $this->response = $response;
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
        $doc['response'] = $this->response;
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
            'executionStatus' => $this->executionStatus->value,
            'completedAt' => $this->completedAt?->format('c'),
        ], $changedProperties);
    }
}
