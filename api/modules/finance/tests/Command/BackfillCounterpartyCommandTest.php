<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Command\BackfillCounterpartyCommand;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:backfill-counterparty` — gives a payee to the history stored
 * before it had a field of its own.
 */
final class BackfillCounterpartyCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $application = new Application();
        $application->add(self::getContainer()->get(BackfillCounterpartyCommand::class));

        $this->tester = new CommandTester($application->find('app:finance:backfill-counterparty'));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function stored(string $label): Transaction
    {
        $this->em()->clear();

        return $this->em()->getRepository(Transaction::class)->findOneBy(['label' => $label])
            ?? self::fail(sprintf('no transaction labelled "%s"', $label));
    }

    public function testItReadsThePayeeFromTheLabelAndLeavesWhatNamesNoOne(): void
    {
        $this->loadFixtures('BackfillCounterpartyCommandTest.yaml');

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();

        $netflix = $this->stored('PRLV SEPA NETFLIX.COM 12/10');
        self::assertSame('PRLV SEPA NETFLIX.COM', $netflix->getCounterpartyName());
        self::assertSame('prlv sepa netflixcom', $netflix->getCounterpartyKey());

        $nobody = $this->stored('12345678');
        self::assertNull($nobody->getCounterpartyName());
        self::assertNull($nobody->getCounterpartyKey());

        // Straight to the database: the lists read the index, the open screens Mercure.
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testTwoMonthsOfTheSameSubscriptionShareTheKey(): void
    {
        $this->loadFixtures('BackfillCounterpartyCommandTest.yaml');

        $this->tester->execute([]);

        self::assertSame(
            $this->stored('PRLV SEPA NETFLIX.COM 12/10')->getCounterpartyKey(),
            $this->stored('PRLV SEPA NETFLIX.COM 12/11')->getCounterpartyKey(),
        );
    }

    public function testItKeepsAPayeeTheBankAlreadyNamed(): void
    {
        $this->loadFixtures('BackfillCounterpartyCommandTest.yaml');

        $this->tester->execute([]);

        $named = $this->stored('PRLV SEPA SPOTIFY 02/09');
        self::assertSame('SPOTIFY AB', $named->getCounterpartyName());
    }

    public function testADryRunReportsWithoutWritingOrPublishing(): void
    {
        $this->loadFixtures('BackfillCounterpartyCommandTest.yaml');

        $this->tester->execute(['--dry-run' => true]);
        $this->tester->assertCommandIsSuccessful();

        self::assertStringContainsString('À renseigner', $this->tester->getDisplay());
        self::assertStringContainsString('Dry run', $this->tester->getDisplay());
        self::assertNull($this->stored('PRLV SEPA NETFLIX.COM 12/10')->getCounterpartyName());
        self::assertSame([], $this->getMercureHub()->getUpdates());
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    public function testRunningItTwiceChangesNothingTheSecondTime(): void
    {
        $this->loadFixtures('BackfillCounterpartyCommandTest.yaml');

        $this->tester->execute([]);
        $first = $this->stored('PRLV SEPA NETFLIX.COM 12/10')->getCounterpartyKey();

        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->tester->execute([]);

        self::assertSame($first, $this->stored('PRLV SEPA NETFLIX.COM 12/10')->getCounterpartyKey());
        self::assertSame([], $this->getMercureHub()->getUpdates(), 'a line already filled is not republished');
        self::assertStringContainsString('0 transaction(s)', $this->tester->getDisplay());
    }
}
