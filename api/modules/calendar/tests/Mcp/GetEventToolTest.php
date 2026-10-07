<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetEventTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetEventToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UserIsolationToolsTest.yaml');
    }

    /** @return array<string, mixed> */
    private function read(string $id): array
    {
        return json_decode((self::getContainer()->get(GetEventTool::class))($id), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testItReadsTheCallersEventByItsId(): void
    {
        $this->loginFixtureUser();
        $event = $this->getFixture('own_event');

        $data = $this->read((string) $event->getId());

        self::assertSame('Own event', $data['event']['summary']);
        self::assertSame('Own agenda', $data['event']['agenda']);
        self::assertSame($event->getStartAt()->format('c'), $data['event']['startAt']);
        self::assertFalse($data['event']['allDay']);
    }

    public function testAnotherUsersEventAnswersLikeAMissingOne(): void
    {
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('other_event')->getId();

        $data = $this->read($id);

        self::assertSame("Event not found: {$id}", $data['error']);
        self::assertArrayNotHasKey('event', $data);
    }

    public function testAnIdThatIsNotAnUlidIsNotFound(): void
    {
        $this->loginFixtureUser();

        self::assertSame('Event not found: nope', $this->read('nope')['error']);
    }

    public function testWithoutUserItIsRefused(): void
    {
        $data = $this->read((string) $this->getFixture('own_event')->getId());

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('event', $data);
    }
}
