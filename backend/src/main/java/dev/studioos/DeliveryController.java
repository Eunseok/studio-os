package dev.studioos;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chief/{id}/delivery")
public class DeliveryController {
  private final DeliveryService service;

  public DeliveryController(DeliveryService service) {
    this.service = service;
  }

  @GetMapping
  public Object get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping
  public Object deliver(@PathVariable String id) {
    return service.approveAndDeliver(id);
  }

  @PostMapping("/retry")
  public Object retry(@PathVariable String id) {
    return service.retry(id);
  }
}
