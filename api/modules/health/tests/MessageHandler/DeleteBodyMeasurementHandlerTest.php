<?php

namespace Maggie\Health\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Message\DeleteBodyMeasurementCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class DeleteBodyMeasurementHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/body_measurement.yaml');
        $this->resetMercure();
    }

    private function exists(string $fixture): bool
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return null !== $em->find(BodyMeasurement::class, (string) $this->getFixture($fixture)->getId());
    }

    private function delete(string $fixture, string $owner = 'test_user'): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DeleteBodyMeasurementCommand(
            userId: (string) $this->getFixture($owner)->getId(),
            measurementId: (string) $this->getFixture($fixture)->getId(),
        ));
    }

    public function testItRemovesAndPublishes(): void
    {
        $this->delete('morning');

        self::assertFalse($this->exists('morning'));
        $this->assertMercureDeletePublished('/api/body-measurements/'.$this->getFixture('morning')->getId());
    }

    public function testAnotherUsersMeasurementIsRefused(): void
    {
        try {
            $this->delete('secret');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertStringContainsString('Measurement not found', (string) $e->getPrevious()?->getMessage());
        }

        self::assertTrue($this->exists('secret'));
        $this->assertMercureUpdateCount(0);
    }
}
