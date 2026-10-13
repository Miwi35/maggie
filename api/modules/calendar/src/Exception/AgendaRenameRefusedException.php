<?php

declare(strict_types=1);

namespace Maggie\Calendar\Exception;

/** A connected agenda's new name did not reach Google: the agenda kept the name Google has. */
final class AgendaRenameRefusedException extends \DomainException
{
}
