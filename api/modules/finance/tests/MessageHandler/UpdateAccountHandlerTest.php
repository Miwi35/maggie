<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Message\UpdateAccountCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateAccountHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('clearable_fields.yaml');
    }

    private function dispatch(UpdateAccountCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(): Account
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Account::class)->find($this->getFixture('bank_account')->getId());
    }

    public function testClearingTheBankLeavesTheOtherFieldsUntouched(): void
    {
        $this->dispatch(new UpdateAccountCommand(
            accountId: (string) $this->getFixture('bank_account')->getId(),
            clearFields: ['bank'],
        ));

        $account = $this->reload();
        self::assertNull($account->getBank());
        self::assertSame('ext-account-123', $account->getExternalAccountId());
        self::assertSame('Compte courant', $account->getName());
        self::assertSame(125000, $account->getBalanceCents());
        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testClearingTheExternalAccountId(): void
    {
        $this->dispatch(new UpdateAccountCommand(
            accountId: (string) $this->getFixture('bank_account')->getId(),
            clearFields: ['externalAccountId'],
        ));

        $account = $this->reload();
        self::assertNull($account->getExternalAccountId());
        self::assertSame('Crédit Agricole', $account->getBank());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateAccountCommand(
            accountId: (string) $this->getFixture('bank_account')->getId(),
            name: 'Renamed',
        ));

        $account = $this->reload();
        self::assertSame('Renamed', $account->getName());
        self::assertSame('Crédit Agricole', $account->getBank());
        self::assertSame('ext-account-123', $account->getExternalAccountId());
    }

    public function testUnknownAccountFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Account not found');

        $this->dispatch(new UpdateAccountCommand(accountId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['bank']));
    }
}
