<?php

namespace Maggie\Health\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Exception\DuplicateBodyMeasurementException;
use Maggie\Health\Exception\InvalidBodyMeasurementException;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateBodyMeasurementHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/body_measurement.yaml');
        $this->resetMercure();
    }

    private function reload(string $fixture): BodyMeasurement
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        $measurement = $em->find(BodyMeasurement::class, (string) $this->getFixture($fixture)->getId());
        \assert($measurement instanceof BodyMeasurement);

        return $measurement;
    }

    private function dispatch(string $fixture, string $owner = 'test_user', mixed ...$fields): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new UpdateBodyMeasurementCommand(...[
            'userId' => (string) $this->getFixture($owner)->getId(),
            'measurementId' => (string) $this->getFixture($fixture)->getId(),
        ] + $fields));
    }

    public function testOnlyTheGivenFieldsChangeAndItPublishes(): void
    {
        $this->dispatch('morning', weightKg: 72.5);

        $morning = $this->reload('morning');
        self::assertSame(72.5, $morning->getWeightKg());
        self::assertSame(21.3, $morning->getBodyFatPercent());
        self::assertFalse($morning->isExcluded());
        $this->assertMercureUpdatePublished('/api/body-measurements/'.$morning->getId());
    }

    /** « Écarter » is an update with excluded: true; excluded: false takes it back. */
    public function testExcludedSetsAsideAndTakesBack(): void
    {
        $this->dispatch('morning', excluded: true);
        self::assertTrue($this->reload('morning')->isExcluded());
        self::assertSame(72.4, $this->reload('morning')->getWeightKg());

        $this->dispatch('morning', excluded: false);
        self::assertFalse($this->reload('morning')->isExcluded());
    }

    public function testAClearFlagEmptiesAFigure(): void
    {
        $this->dispatch('morning', clearBodyFat: true);

        $morning = $this->reload('morning');
        self::assertNull($morning->getBodyFatPercent());
        self::assertSame(72.4, $morning->getWeightKg());
    }

    public function testClearingTheOnlyFigureIsRefused(): void
    {
        try {
            $this->dispatch('waist', clearWaist: true);
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(InvalidBodyMeasurementException::class, $e->getPrevious());
        }

        self::assertSame(84.5, $this->reload('waist')->getWaistCm());
        $this->assertMercureUpdateCount(0);
    }

    public function testARefusalLeavesTheMeasurementAsItWas(): void
    {
        try {
            $this->dispatch('morning', weightKg: 70.0, measuredAt: '2026-10-21');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(InvalidBodyMeasurementException::class, $e->getPrevious());
            self::assertSame('measuredAt', $e->getPrevious()->field);
        }

        self::assertSame(72.4, $this->reload('morning')->getWeightKg());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnExternalIdOfAnotherMeasurementOfTheUserIsRefused(): void
    {
        try {
            $this->dispatch('morning', externalId: 'hc-1');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(DuplicateBodyMeasurementException::class, $e->getPrevious());
        }

        self::assertNull($this->reload('morning')->getExternalId());
    }

    public function testAnotherUsersMeasurementIsRefused(): void
    {
        try {
            $this->dispatch('secret', excluded: true);
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertStringContainsString('Measurement not found', (string) $e->getPrevious()?->getMessage());
        }

        self::assertFalse($this->reload('secret')->isExcluded());
        $this->assertMercureUpdateCount(0);
    }
}
