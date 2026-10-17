<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\UseCase\GetCushionStatus;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The cushion is what its accounts hold less what is lent to people and not
 * given back (MAG-454). The world is `cushion_peer_claims.yaml`: a Livret A of
 * 5 200 € flagged as cushion and 535 € of open créances.
 */
class GetCushionStatusPeerClaimsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetCushionStatus $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetCushionStatus::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures(__DIR__.'/../Api/fixtures/cushion_peer_claims.yaml');
    }

    /** @return array<string, mixed> */
    private function cushionStatus(): array
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');

        return $this->useCase->execute($user);
    }

    private function repay(string $share, int $cents): void
    {
        $this->em->find(PeerDebtShare::class, $this->getFixture($share)->getId())->setRepaidCents($cents);
        $this->em->flush();
    }

    public function testTheOpenCréancesLowerTheCushion(): void
    {
        $status = $this->cushionStatus();

        // 5 200 € - 535 €: only the open créances count — not the one given back in
        // full, not the dette of 280 €, not the 800 € treasury advance already
        // out of the Livret A, not another user's.
        self::assertSame(53500, $status['peerClaimsCents']);
        self::assertSame(466500, $status['currentCents']);
        self::assertEqualsCanonicalizing(['Gîte de Crozon', 'Anniversaire de Léa', 'Hellfest'], $status['peerClaimObjects']);
        self::assertSame(750000 - 466500, $status['deficitCents']);
        self::assertSame('building', $status['state']);
        self::assertTrue($status['blocksGreenScore']);
        // The accounts keep their own balance: the claim is a line of its own.
        self::assertSame(520000, $status['accounts'][0]['balanceCents']);
    }

    public function testEachRepaymentFillsTheCushionBackUp(): void
    {
        $this->repay('gite_julie', 20000);

        $status = $this->cushionStatus();

        self::assertSame(33500, $status['peerClaimsCents']);
        self::assertSame(486500, $status['currentCents']);
    }

    public function testAClaimGivenBackInFullLeavesTheList(): void
    {
        $this->repay('hellfest_lucas', 9000);

        $status = $this->cushionStatus();

        self::assertSame(44500, $status['peerClaimsCents']);
        self::assertNotContains('Hellfest', $status['peerClaimObjects']);
    }

    public function testWithoutAnyClaimTheCushionIsTheAccountsAlone(): void
    {
        foreach (['gite_julie' => 20000, 'gite_tom' => 20000, 'birthday_tom' => 4500, 'hellfest_lucas' => 9000] as $share => $cents) {
            $this->em->find(PeerDebtShare::class, $this->getFixture($share)->getId())->setRepaidCents($cents);
        }
        $this->em->flush();

        $status = $this->cushionStatus();

        self::assertSame(0, $status['peerClaimsCents']);
        self::assertSame([], $status['peerClaimObjects']);
        self::assertSame(520000, $status['currentCents']);
    }

    /** The score reads this value: a net that is full only thanks to money lent out is not full. */
    public function testAClaimCanKeepAnOtherwiseFullCushionFromBeingComplete(): void
    {
        $livret = $this->em->find(Account::class, $this->getFixture('livret')->getId());
        $livret->setBalanceCents(780000);
        $this->em->flush();

        $status = $this->cushionStatus();

        self::assertSame(726500, $status['currentCents']);
        self::assertSame('building', $status['state']);
        self::assertTrue($status['blocksGreenScore']);

        $this->repay('gite_julie', 20000);
        $this->repay('gite_tom', 20000);

        $status = $this->cushionStatus();

        self::assertSame(766500, $status['currentCents']);
        self::assertSame('complete', $status['state']);
        self::assertFalse($status['blocksGreenScore']);
    }

    public function testClaimsBiggerThanTheAccountsNeverGiveANegativeCoverage(): void
    {
        $livret = $this->em->find(Account::class, $this->getFixture('livret')->getId());
        $livret->setBalanceCents(10000);
        $this->em->flush();

        $status = $this->cushionStatus();

        self::assertSame(-43500, $status['currentCents']);
        self::assertSame(0, $status['coveragePercent']);
        self::assertSame(0.0, $status['monthsCovered']);
    }
}
