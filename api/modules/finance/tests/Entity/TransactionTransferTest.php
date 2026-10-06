<?php

namespace Maggie\Finance\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use PHPUnit\Framework\TestCase;

/**
 * The transfer marking on the two channels a client reads it from.
 *
 * A badge the list shows and the detail screen does not — or the other way
 * round — is the disagreement `isCushion` already pays for: the search index
 * and the Mercure payload have to spell the same three fields.
 */
class TransactionTransferTest extends TestCase
{
    private User $user;
    private Account $checking;
    private Account $savings;
    private Transaction $out;
    private Transaction $in;

    protected function setUp(): void
    {
        $user = $this->user = new User();
        $user->setEmail('virements@example.com');
        $user->setGoogleId('google-virements');
        $user->setName('Virements');

        $this->checking = (new Account())->setName('Courant')->setType(AccountType::Checking)->setUser($user);
        $this->savings = (new Account())->setName('Livret')->setType(AccountType::Savings)->setUser($user);

        $this->out = $this->movement($this->savings, -300000, '2026-09-12', 'Virement vers Courant');
        $this->in = $this->movement($this->checking, 300000, '2026-09-13', 'Virement du Livret');
    }

    private function movement(Account $account, int $amountCents, string $bookedAt, string $label): Transaction
    {
        return (new Transaction())
            ->setUser($this->user)
            ->setAccount($account)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel($label);
    }

    public function testAFreshMovementIsNoTransferAndNobodyDecidedItByHand(): void
    {
        self::assertFalse($this->out->isInternalTransfer());
        self::assertSame(TransferKind::None, $this->out->getTransferKind());
        self::assertSame(TransferSource::Auto, $this->out->getTransferSource());
        self::assertNull($this->out->getCounterpart());
    }

    public function testMarkingOneLegMarksBothAndTheyPointAtEachOther(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Auto);

        self::assertTrue($this->out->isInternalTransfer());
        self::assertTrue($this->in->isInternalTransfer());
        self::assertSame($this->in, $this->out->getCounterpart());
        self::assertSame($this->out, $this->in->getCounterpart());
        self::assertSame(TransferSource::Auto, $this->in->getTransferSource());
    }

    public function testReleasingOneLegUnpairsBothAndSealsOnlyTheOneJudged(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Auto);

        $this->out->releaseInternalTransfer(TransferSource::Manual);

        self::assertFalse($this->out->isInternalTransfer());
        self::assertFalse($this->in->isInternalTransfer());
        self::assertNull($this->out->getCounterpart());
        self::assertNull($this->in->getCounterpart());
        self::assertSame(TransferSource::Manual, $this->out->getTransferSource(), 'the line he judged is sealed');
        self::assertSame(
            TransferSource::Auto,
            $this->in->getTransferSource(),
            'the other leg stays free to pair with the line it really belongs to',
        );
    }

    public function testReleasingAHandMadePairFreesTheOtherLegForTheDetection(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Manual);

        $this->out->releaseInternalTransfer(TransferSource::Manual);

        self::assertSame(TransferSource::Manual, $this->out->getTransferSource());
        self::assertSame(
            TransferSource::Auto,
            $this->in->getTransferSource(),
            'the pair cannot come back — the sealed line is skipped — but this leg may face a third one',
        );
    }

    public function testPairingAgainFreesTheLineTheLegNoLongerFaces(): void
    {
        $third = $this->movement($this->savings, -300000, '2026-09-20', 'Autre virement sortant');
        $this->in->markAsInternalTransfer($third, TransferSource::Auto);

        $this->out->markAsInternalTransfer($this->in, TransferSource::Manual);

        self::assertSame($this->in, $this->out->getCounterpart());
        self::assertSame($this->out, $this->in->getCounterpart());
        self::assertFalse(
            $third->isInternalTransfer(),
            'a line left pointing at a line that disowns it would leave every aggregate for good',
        );
        self::assertNull($third->getCounterpart());
    }

    public function testTheMercurePayloadCarriesTheThreeFields(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Manual);

        $payload = $this->out->toMercurePayload();

        self::assertSame('internal', $payload['transferKind']);
        self::assertSame('manual', $payload['transferSource']);
        self::assertSame((string) $this->in->getId(), $payload['counterpartId']);
    }

    public function testADifferentialUpdateOfTheCounterpartStillNamesIt(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Auto);

        $payload = $this->out->toMercurePayload(['transferKind', 'transferSource', 'counterpart']);

        self::assertSame(
            ['transferKind' => 'internal', 'transferSource' => 'auto', 'counterpartId' => (string) $this->in->getId()],
            $payload,
        );
    }

    public function testTheSearchDocumentCarriesTheThreeFields(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Auto);

        $document = $this->out->toSearchDocument();

        self::assertSame('internal', $document['transferKind']);
        self::assertSame('auto', $document['transferSource']);
        self::assertSame((string) $this->in->getId(), $document['counterpartId']);
    }

    public function testTheMarkingSurvivesTheRoundTripThroughElasticsearch(): void
    {
        $this->out->markAsInternalTransfer($this->in, TransferSource::Manual);

        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getReference')->willReturnCallback(
            fn (string $class, mixed $id): ?object => match (true) {
                User::class === $class => $this->user,
                Account::class === $class => $this->savings,
                (string) $this->in->getId() === (string) $id => $this->in,
                default => null,
            },
        );

        $source = $this->out->toSearchDocument();
        $source['id'] = (string) $this->out->getId();

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate($source, Transaction::class);
        self::assertInstanceOf(Transaction::class, $hydrated);

        self::assertTrue($hydrated->isInternalTransfer());
        self::assertSame(TransferSource::Manual, $hydrated->getTransferSource());
        self::assertSame((string) $this->in->getId(), (string) $hydrated->getCounterpart()?->getId());
    }

    public function testASingleLeggedTransferComesBackWithoutACounterpart(): void
    {
        $this->out->setTransferKind(TransferKind::Internal)->setTransferSource(TransferSource::Manual);

        $document = $this->out->toSearchDocument();

        self::assertSame('internal', $document['transferKind']);
        self::assertNull($document['counterpartId']);
    }
}
