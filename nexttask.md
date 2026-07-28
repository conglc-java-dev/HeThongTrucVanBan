task mới:
Hoàn thiện quy trình đăng ký và phê duyệt tổ chức tham gia hệ thống Trục liên thông văn bản.
1.Thay đổi trạng thái mặc định của tổ chức khi đăng ký:
Mục tiêu
Điều chỉnh quy trình đăng ký tổ chức để mọi tổ chức mới sau khi gửi yêu cầu tham gia hệ thống đều được tạo với trạng thái PENDING_APPROVAL thay vì ACTIVE, đảm bảo chỉ những tổ chức đã được Hub Admin kiểm duyệt mới được phép tham gia mạng lưới. Theo đặc tả API, endpoint đăng ký phải lưu tổ chức ở trạng thái chờ duyệt. 
Phạm vi công việc
 Thay đổi trạng thái mặc định khi tạo Organization. 
 Cập nhật logic Service nếu đang gán mặc định ACTIVE. 
 Kiểm tra các API liên quan không bị ảnh hưởng. 
 Bổ sung hoặc cập nhật Unit Test. 
Kết quả mong đợi
 Organization mới được tạo với trạng thái PENDING_APPROVAL. 
 Tổ chức chưa được phê duyệt không thể tham gia giao dịch
2. Xây dựng API phê duyệt tổ chức
Mục tiêu
Cho phép Hub Admin phê duyệt hoặc từ chối yêu cầu đăng ký của tổ chức. Khi được phê duyệt, trạng thái chuyển sang ACTIVE; nếu từ chối, lưu lý do từ chối theo đặc tả API. 
Phạm vi công việc
 Xây dựng API PUT /registry/organizations/{code}/approve. 
 Kiểm tra quyền của Hub Admin. 
 Cập nhật trạng thái Organization. 
 Lưu lý do từ chối (nếu có). 
 Trả về response đúng theo API Specification. 
Kết quả mong đợi
 Admin có thể phê duyệt hoặc từ chối tổ chức. 
 Chỉ tổ chức ACTIVE mới được Routing và Exchange sử dụng.
3. cập nhật file run.md để kịp thời hướng dẫn sử dụng