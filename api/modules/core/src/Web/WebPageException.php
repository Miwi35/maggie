<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

/** A page that was refused or could not be read; the message is the reason the model is told. */
class WebPageException extends \RuntimeException
{
}
