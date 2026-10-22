<?php

declare(strict_types=1);

namespace Maggie\Finance\Serializer;

use ApiPlatform\Metadata\CollectionOperationInterface;
use ApiPlatform\Metadata\IriConverterInterface;
use ApiPlatform\Metadata\Operation;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;
use Symfony\Contracts\Service\ResetInterface;

/**
 * A line of `GET /api/transactions` written straight from the entity (MAG-544).
 *
 * The generic JSON-LD normalizer spends about a millisecond per line walking
 * the metadata of 36 properties, and a page is up to 100 lines — it was the
 * whole cost of the list (the database and Elasticsearch answer in a few
 * milliseconds). The shape is the same, field for field:
 * `TransactionListNormalizerTest` compares the two on lines of every kind and
 * fails until a field added to `Transaction` is written here too.
 *
 * Only the collection of the resource is written here; an item, a creation or
 * an update keeps the generic path.
 */
final class TransactionListNormalizer implements NormalizerInterface, ResetInterface
{
    /** Context key that forces the generic normalizer — the reference the equivalence test compares to. */
    public const string GENERIC = 'transaction_list_normalizer_generic';

    /** @var array<string, string> IRI by resource class and id: the account, the user and the categories repeat on every line */
    private array $iris = [];

    private int $written = 0;

    public function __construct(private readonly IriConverterInterface $iriConverter)
    {
    }

    public function normalize(mixed $data, ?string $format = null, array $context = []): array
    {
        \assert($data instanceof Transaction);
        ++$this->written;

        $qualification = $data->getQualification();
        $kind = $qualification->getKind();

        $fields = [
            '@id' => $this->iri($data),
            '@type' => 'Transaction',
            'id' => (string) $data->getId(),
            'account' => $this->iri($data->getAccount()),
            'category' => $this->iriOrNull($data->getCategory()),
            'amountCents' => $data->getAmountCents(),
            'currency' => $data->getCurrency(),
            'bookedAt' => $data->getBookedAt()->format(\DateTimeInterface::RFC3339),
            'label' => $data->getLabel(),
            'counterpartyName' => $data->getCounterpartyName(),
            'externalId' => $data->getExternalId(),
            'counterpartyKey' => $data->getCounterpartyKey(),
            'status' => $data->getStatus()->value,
            'retrospect' => $data->getRetrospect()->value,
            'categorySource' => $data->getCategorySource()->value,
            'qualification' => [
                '@type' => 'Qualification',
                'mandatory' => $qualification->isMandatory(),
                'kind' => $kind->value,
            ],
            'qualifiedBySeries' => $this->iriOrNull($data->getQualifiedBySeries()),
            'qualifiedByRule' => $this->iriOrNull($data->getQualifiedByRule()),
            'categorizedAt' => $data->getCategorizedAt()?->format(\DateTimeInterface::RFC3339),
            'transferKind' => $data->getTransferKind()->value,
            'transferSource' => $data->getTransferSource()->value,
            'counterpart' => $this->iriOrNull($data->getCounterpart()),
            'rejectionSettledAt' => $data->getRejectionSettledAt()?->format(\DateTimeInterface::RFC3339),
            'repays' => $this->iriOrNull($data->getRepays()),
            'recurringOperation' => $this->iriOrNull($data->getRecurringOperation()),
            'recurringOccurrenceOn' => $data->getRecurringOccurrenceOn()?->format(\DateTimeInterface::RFC3339),
            'recurringSource' => $data->getRecurringSource()->value,
            'oneOffEnvelope' => $this->iriOrNull($data->getOneOffEnvelope()),
            'note' => $data->getNote(),
            'contact' => $this->iriOrNull($data->getContact()),
            'myShareCents' => $data->getMyShareCents(),
            'peerKind' => $data->getPeerKind()?->value,
            'peerAllocatedCents' => $data->getPeerAllocatedCents(),
            'user' => $this->iri($data->getUser()),
            'exceptional' => $data->isExceptional(),
            'qualifiedBy' => $data->getQualifiedBy(),
        ];

        // API Platform skips the null fields unless the operation says otherwise.
        return false === ($context['skip_null_values'] ?? true)
            ? $fields
            : array_filter($fields, static fn (mixed $value): bool => null !== $value);
    }

    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        $operation = $context['root_operation'] ?? null;

        return $data instanceof Transaction
            && 'jsonld' === $format
            && !isset($context[self::GENERIC])
            && !isset($context['attributes'])
            && !isset($context['groups'])
            && Transaction::class === ($context['resource_class'] ?? null)
            && true === ($context['api_collection_sub_level'] ?? false)
            && $operation instanceof Operation
            && $operation instanceof CollectionOperationInterface
            && Transaction::class === $operation->getClass();
    }

    /** @return array<class-string, bool> */
    public function getSupportedTypes(?string $format): array
    {
        return [Transaction::class => false];
    }

    /** Lines written by this writer since the kernel booted: lets a test prove the list takes this path. */
    public function written(): int
    {
        return $this->written;
    }

    public function reset(): void
    {
        $this->iris = [];
    }

    private function iriOrNull(Account|Category|Contact|Envelope|RecurringOperation|Transaction|null $resource): ?string
    {
        return null === $resource ? null : $this->iri($resource);
    }

    private function iri(Account|Category|Contact|Envelope|RecurringOperation|Transaction|User $resource): string
    {
        $key = $resource::class.'|'.$resource->getId();

        return $this->iris[$key] ??= $this->iriConverter->getIriFromResource($resource);
    }
}
