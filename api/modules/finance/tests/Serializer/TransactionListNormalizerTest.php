<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Serializer;

use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerKind;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Serializer\TransactionListNormalizer;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The fast writer of the list says exactly what the generic JSON-LD
 * normalizer says (MAG-544). A field added to `Transaction` that the fast
 * writer does not know fails here, not in the admin.
 */
final class TransactionListNormalizerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/account_incidents.yaml');
    }

    public function testABareLineIsWrittenAsTheGenericNormalizerWritesIt(): void
    {
        $line = $this->line()->setAmountCents(-4200)->setLabel('Courses');

        self::assertSame($this->generic($line), $this->fast($line));
    }

    public function testALineWithEveryFieldSetIsWrittenAsTheGenericNormalizerWritesIt(): void
    {
        $user = $this->getFixture('test_user');
        $loan = (new Category())->setName('Prêt')->setUser($user);
        $operation = (new RecurringOperation())->setUser($user)->setCategory($loan)->setPeriod(RecurrencePeriod::Monthly);
        $line = $this->line()
            ->setAmountCents(-80000)
            ->setLabel('Gîte')
            ->setCategory((new Category())->setName('Vacances')->setUser($user))
            ->setCounterpartyName('  Gîtes   de France ')
            ->setExternalId('ref-1')
            ->setIsExceptional(true)
            ->setRetrospect(RetrospectVerdict::Unrated)
            ->setNote('Pour août')
            ->setContact((new Contact())->setName('Sam')->setUser($user))
            ->setMyShareCents(20000)
            ->setPeerKind(PeerKind::Income)
            ->setPeerAllocatedCents(500)
            ->setOneOffEnvelope(new Envelope())
            ->setControlledAt(new \DateTimeImmutable('2026-10-03'))
            ->setRepays($this->line())
            ->attachToRecurring($operation, new \DateTimeImmutable('2026-10-01'), RecurringLinkSource::Manual)
            ->setCategorizedAt(new \DateTimeImmutable('2026-10-02T10:30:00+02:00'))
            ->qualify(new Qualification(true, QualificationKind::Repayment, $loan), $operation)
            ->markAsRejection($this->line()->setAmountCents(80000), TransferSource::Manual)
            ->settleRejection(new \DateTimeImmutable('2026-10-04T08:00:00+00:00'));

        self::assertSame($this->generic($line), $this->fast($line));
    }

    public function testATransferIsWrittenAsTheGenericNormalizerWritesIt(): void
    {
        $line = $this->line()->setAmountCents(-30000);
        $line->markAsInternalTransfer($this->line()->setAmountCents(30000), TransferSource::Auto);

        self::assertSame($this->generic($line), $this->fast($line));
    }

    public function testNullFieldsAreKeptOrSkippedAsTheContextAsks(): void
    {
        $line = $this->line();

        foreach ([true, false] as $skip) {
            self::assertSame($this->generic($line, $skip), $this->fast($line, $skip));
        }
        self::assertArrayNotHasKey('note', $this->fast($line, true));
        self::assertArrayHasKey('note', $this->fast($line, false));
    }

    public function testOnlyTheCollectionIsWrittenByTheFastWriter(): void
    {
        $normalizer = self::getContainer()->get(TransactionListNormalizer::class);
        $line = $this->line();

        self::assertTrue($normalizer->supportsNormalization($line, 'jsonld', $this->context(collection: true)));
        self::assertFalse($normalizer->supportsNormalization($line, 'jsonld', $this->context(collection: false)));
        self::assertFalse($normalizer->supportsNormalization($line, 'json', $this->context(collection: true)));
        self::assertFalse($normalizer->supportsNormalization($line, 'jsonld', ['resource_class' => Account::class] + $this->context(collection: true)));
        self::assertFalse($normalizer->supportsNormalization($line, 'jsonld', ['attributes' => ['label']] + $this->context(collection: true)));
    }

    private function line(): Transaction
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $account = $this->getFixture('checking');
        \assert($account instanceof Account);

        return (new Transaction())
            ->setUser($user)
            ->setAccount($account)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable('2026-10-01'))
            ->setLabel('Ligne')
            ->setStatus(TransactionStatus::Spent);
    }

    /** @return array<string, mixed> */
    private function generic(Transaction $line, bool $skipNull = true): array
    {
        return $this->normalize($line, [TransactionListNormalizer::GENERIC => true], $skipNull);
    }

    /** @return array<string, mixed> */
    private function fast(Transaction $line, bool $skipNull = true): array
    {
        $written = $this->normalize($line, [], $skipNull);

        self::assertSame(array_keys($this->generic($line, $skipNull)), array_keys($written), 'The fields, in the same order.');

        return $written;
    }

    /**
     * @param array<string, mixed> $extra
     *
     * @return array<string, mixed>
     */
    private function normalize(Transaction $line, array $extra, bool $skipNull): array
    {
        $serializer = self::getContainer()->get('serializer');
        \assert($serializer instanceof NormalizerInterface);

        $written = $serializer->normalize($line, 'jsonld', $extra + ['skip_null_values' => $skipNull] + $this->context(collection: true));
        \assert(\is_array($written));

        return $written;
    }

    /**
     * What a member of the collection is written with: the operation travels
     * as `root_operation`, a lone item carries its own as `operation`.
     *
     * @return array<string, mixed>
     */
    private function context(bool $collection): array
    {
        $operation = self::getContainer()->get(ResourceMetadataCollectionFactoryInterface::class)
            ->create(Transaction::class)->getOperation(forceCollection: $collection);

        $context = ['resource_class' => Transaction::class, 'request_uri' => '/api/transactions', 'jsonld_has_context' => true];

        return $collection
            ? $context + ['api_sub_level' => true, 'api_collection_sub_level' => true, 'root_operation' => $operation, 'root_operation_name' => $operation->getName()]
            : $context + ['operation' => $operation, 'operation_name' => $operation->getName()];
    }
}
