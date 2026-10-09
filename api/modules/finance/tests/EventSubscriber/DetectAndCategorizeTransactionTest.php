<?php

namespace Maggie\Finance\Tests\EventSubscriber;

use Maggie\Finance\Event\TransactionChanged;
use Maggie\Finance\Event\TransactionRecorded;
use Maggie\Finance\Event\TransactionRemoved;
use Maggie\Finance\EventSubscriber\DetectAndCategorizeTransaction;
use Maggie\Finance\EventSubscriber\ReleaseCounterpartOnTransactionRemoved;
use Maggie\Finance\Message\CategorizeTransactionCommand;
use Maggie\Finance\Message\DetectInternalTransferCommand;
use Maggie\Finance\Message\DetectRejectionCommand;
use Maggie\Finance\Message\ReleaseCounterpartCommand;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class DetectAndCategorizeTransactionTest extends TestCase
{
    private function bus(): object
    {
        return new class implements MessageBusInterface {
            /** @var list<object> */
            public array $sent = [];

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->sent[] = $message;

                return new Envelope($message);
            }
        };
    }

    public function testARecordedTransactionIsDetectedAsTransferThenRejectionThenCategorized(): void
    {
        $bus = $this->bus();

        (new DetectAndCategorizeTransaction($bus))->onRecorded(new TransactionRecorded('01J0TX'));

        self::assertEquals([
            new DetectInternalTransferCommand('01J0TX'),
            new DetectRejectionCommand('01J0TX'),
            new CategorizeTransactionCommand('01J0TX'),
        ], $bus->sent);
    }

    public function testAChangedTransactionGoesThroughTheSameThreeStepsInTheSameOrder(): void
    {
        $bus = $this->bus();

        (new DetectAndCategorizeTransaction($bus))->onChanged(new TransactionChanged('01J0TX', ['label']));

        self::assertEquals([
            new DetectInternalTransferCommand('01J0TX'),
            new DetectRejectionCommand('01J0TX'),
            new CategorizeTransactionCommand('01J0TX'),
        ], $bus->sent);
    }

    public function testARemovedTransactionReleasesItsCounterpart(): void
    {
        $bus = $this->bus();

        (new ReleaseCounterpartOnTransactionRemoved($bus))(new TransactionRemoved('01J0GONE', '01J0OTHER'));

        self::assertEquals([new ReleaseCounterpartCommand('01J0OTHER', '01J0GONE')], $bus->sent);
    }
}
