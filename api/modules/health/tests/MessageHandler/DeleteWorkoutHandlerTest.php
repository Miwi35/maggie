<?php

namespace Maggie\Health\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Message\DeleteWorkoutCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class DeleteWorkoutHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/workout.yaml');
        $this->resetMercure();
    }

    private function exists(string $fixture): bool
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return null !== $em->find(Workout::class, (string) $this->getFixture($fixture)->getId());
    }

    private function delete(string $fixture, string $owner = 'test_user'): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DeleteWorkoutCommand(
            userId: (string) $this->getFixture($owner)->getId(),
            workoutId: (string) $this->getFixture($fixture)->getId(),
        ));
    }

    public function testItRemovesAndPublishes(): void
    {
        $this->delete('run');

        self::assertFalse($this->exists('run'));
        $this->assertMercureDeletePublished('/api/workouts/'.$this->getFixture('run')->getId());
    }

    public function testAnotherUsersWorkoutIsRefused(): void
    {
        try {
            $this->delete('secret');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertStringContainsString('Workout not found', (string) $e->getPrevious()?->getMessage());
        }

        self::assertTrue($this->exists('secret'));
        $this->assertMercureUpdateCount(0);
    }
}
