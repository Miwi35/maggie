<?php

namespace Maggie\Health\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Enum\MeasurementSource;
use Maggie\Health\Exception\DuplicateBodyMeasurementException;
use Maggie\Health\Exception\InvalidBodyMeasurementException;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class CreateBodyMeasurementHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/body_measurement.yaml');
        $this->resetMercure();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function command(?string $userId = null, string $measuredAt = '2026-10-24T07:00:00+02:00', ?float $weightKg = 72.1, ?float $bodyFatPercent = null, string $source = 'manual', ?string $externalId = null): CreateBodyMeasurementCommand
    {
        return new CreateBodyMeasurementCommand(
            userId: $userId ?? (string) $this->getFixture('test_user')->getId(),
            measuredAt: $measuredAt,
            weightKg: $weightKg,
            bodyFatPercent: $bodyFatPercent,
            source: $source,
            externalId: $externalId,
        );
    }

    public function testItSavesAndPublishes(): void
    {
        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command(bodyFatPercent: 21.04, source: 'health_connect', externalId: 'hc-9'));

        $measurement = $envelope->last(HandledStamp::class)?->getResult();
        self::assertInstanceOf(BodyMeasurement::class, $measurement);
        $reloaded = $this->em()->find(BodyMeasurement::class, (string) $measurement->getId());
        self::assertInstanceOf(BodyMeasurement::class, $reloaded);
        self::assertSame(72.1, $reloaded->getWeightKg());
        // Rounded to the column's scale.
        self::assertSame(21.0, $reloaded->getBodyFatPercent());
        self::assertSame(MeasurementSource::HealthConnect, $reloaded->getSource());
        self::assertSame('hc-9', $reloaded->getExternalId());
        self::assertFalse($reloaded->isExcluded());
        $this->assertMercureUpdatePublished('/api/body-measurements/'.$measurement->getId());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function refusals(): iterable
    {
        yield 'no figure' => [['weightKg' => null]];
        yield 'weight zero' => [['weightKg' => 0.0]];
        yield 'weight rounded to zero' => [['weightKg' => 0.004]];
        yield 'weight too heavy' => [['weightKg' => 500.01]];
        yield 'body fat of 100 %' => [['bodyFatPercent' => 100.0]];
        yield 'unknown source' => [['source' => 'scale']];
        yield 'date only' => [['measuredAt' => '2026-10-24']];
        yield 'no offset' => [['measuredAt' => '2026-10-24T07:00:00']];
    }

    /** @param array<string, mixed> $override */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusals')]
    public function testARefusalWritesAndPublishesNothing(array $override): void
    {
        try {
            self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command(...$override));
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(InvalidBodyMeasurementException::class, $e->getPrevious());
        }

        self::assertSame(7, $this->em()->getRepository(BodyMeasurement::class)->count([]));
        $this->assertMercureUpdateCount(0);
    }

    public function testADuplicateExternalIdOfTheSameUserIsRefused(): void
    {
        try {
            self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command(externalId: 'hc-1'));
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(DuplicateBodyMeasurementException::class, $e->getPrevious());
            self::assertSame('externalId', $e->getPrevious()->field);
        }

        self::assertSame(7, $this->em()->getRepository(BodyMeasurement::class)->count([]));
        $this->assertMercureUpdateCount(0);
    }

    public function testTheSameExternalIdForAnotherUserIsKept(): void
    {
        $other = (string) $this->getFixture('other_user')->getId();
        self::getContainer()->get(MessageBusInterface::class)->dispatch($this->command(userId: $other, externalId: 'hc-new'));

        self::assertSame(8, $this->em()->getRepository(BodyMeasurement::class)->count([]));
    }
}
