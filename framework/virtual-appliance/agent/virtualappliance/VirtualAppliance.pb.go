// Code generated by protoc-gen-go. DO NOT EDIT.
// source: VirtualAppliance.proto

package virtualappliance

import (
	context "context"
	fmt "fmt"
	proto "github.com/golang/protobuf/proto"
	grpc "google.golang.org/grpc"
	codes "google.golang.org/grpc/codes"
	status "google.golang.org/grpc/status"
	math "math"
)

// Reference imports to suppress errors if they are not otherwise used.
var _ = proto.Marshal
var _ = fmt.Errorf
var _ = math.Inf

// This is a compile-time assertion to ensure that this generated file
// is compatible with the proto package it is being compiled against.
// A compilation error at this line likely means your copy of the
// proto package needs to be updated.
const _ = proto.ProtoPackageIsVersion3 // please upgrade the proto package

type PingRequest struct {
	Message              string   `protobuf:"bytes,1,opt,name=message,proto3" json:"message,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *PingRequest) Reset()         { *m = PingRequest{} }
func (m *PingRequest) String() string { return proto.CompactTextString(m) }
func (*PingRequest) ProtoMessage()    {}
func (*PingRequest) Descriptor() ([]byte, []int) {
	return fileDescriptor_1dcc0045f14d07fe, []int{0}
}

func (m *PingRequest) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_PingRequest.Unmarshal(m, b)
}
func (m *PingRequest) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_PingRequest.Marshal(b, m, deterministic)
}
func (m *PingRequest) XXX_Merge(src proto.Message) {
	xxx_messageInfo_PingRequest.Merge(m, src)
}
func (m *PingRequest) XXX_Size() int {
	return xxx_messageInfo_PingRequest.Size(m)
}
func (m *PingRequest) XXX_DiscardUnknown() {
	xxx_messageInfo_PingRequest.DiscardUnknown(m)
}

var xxx_messageInfo_PingRequest proto.InternalMessageInfo

func (m *PingRequest) GetMessage() string {
	if m != nil {
		return m.Message
	}
	return ""
}

type PingResponse struct {
	Message              string   `protobuf:"bytes,1,opt,name=message,proto3" json:"message,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *PingResponse) Reset()         { *m = PingResponse{} }
func (m *PingResponse) String() string { return proto.CompactTextString(m) }
func (*PingResponse) ProtoMessage()    {}
func (*PingResponse) Descriptor() ([]byte, []int) {
	return fileDescriptor_1dcc0045f14d07fe, []int{1}
}

func (m *PingResponse) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_PingResponse.Unmarshal(m, b)
}
func (m *PingResponse) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_PingResponse.Marshal(b, m, deterministic)
}
func (m *PingResponse) XXX_Merge(src proto.Message) {
	xxx_messageInfo_PingResponse.Merge(m, src)
}
func (m *PingResponse) XXX_Size() int {
	return xxx_messageInfo_PingResponse.Size(m)
}
func (m *PingResponse) XXX_DiscardUnknown() {
	xxx_messageInfo_PingResponse.DiscardUnknown(m)
}

var xxx_messageInfo_PingResponse proto.InternalMessageInfo

func (m *PingResponse) GetMessage() string {
	if m != nil {
		return m.Message
	}
	return ""
}

func init() {
	proto.RegisterType((*PingRequest)(nil), "virtualappliance.PingRequest")
	proto.RegisterType((*PingResponse)(nil), "virtualappliance.PingResponse")
}

func init() { proto.RegisterFile("VirtualAppliance.proto", fileDescriptor_1dcc0045f14d07fe) }

var fileDescriptor_1dcc0045f14d07fe = []byte{
	// 184 bytes of a gzipped FileDescriptorProto
	0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff, 0xe2, 0x12, 0x0b, 0xcb, 0x2c, 0x2a,
	0x29, 0x4d, 0xcc, 0x71, 0x2c, 0x28, 0xc8, 0xc9, 0x4c, 0xcc, 0x4b, 0x4e, 0xd5, 0x2b, 0x28, 0xca,
	0x2f, 0xc9, 0x17, 0x12, 0x28, 0x83, 0x88, 0x27, 0xc2, 0xc4, 0x95, 0xd4, 0xb9, 0xb8, 0x03, 0x32,
	0xf3, 0xd2, 0x83, 0x52, 0x0b, 0x4b, 0x53, 0x8b, 0x4b, 0x84, 0x24, 0xb8, 0xd8, 0x73, 0x53, 0x8b,
	0x8b, 0x13, 0xd3, 0x53, 0x25, 0x18, 0x15, 0x18, 0x35, 0x38, 0x83, 0x60, 0x5c, 0x25, 0x0d, 0x2e,
	0x1e, 0x88, 0xc2, 0xe2, 0x82, 0xfc, 0xbc, 0xe2, 0x54, 0xdc, 0x2a, 0x8d, 0x22, 0xb9, 0xf8, 0xe0,
	0xf6, 0x3a, 0xa6, 0xa7, 0xe6, 0x95, 0x08, 0xb9, 0x73, 0xb1, 0x80, 0xf4, 0x0a, 0xc9, 0xea, 0xa1,
	0xdb, 0xaf, 0x87, 0x64, 0xb9, 0x94, 0x1c, 0x2e, 0x69, 0x88, 0x95, 0x4a, 0x0c, 0x4e, 0xee, 0x5c,
	0xfa, 0xf9, 0x45, 0xe9, 0x7a, 0x89, 0x05, 0x89, 0xc9, 0x19, 0xa9, 0x7a, 0xc9, 0x39, 0xf9, 0xa5,
	0x29, 0xc5, 0x25, 0x89, 0xc9, 0xd9, 0x98, 0x1a, 0xc1, 0xfe, 0x4d, 0x2a, 0x4d, 0x73, 0x12, 0x42,
	0x0f, 0x8a, 0x00, 0xa7, 0x00, 0xc6, 0x24, 0x36, 0xb0, 0xbc, 0x31, 0x20, 0x00, 0x00, 0xff, 0xff,
	0xa7, 0xe9, 0x61, 0x15, 0x29, 0x01, 0x00, 0x00,
}

// Reference imports to suppress errors if they are not otherwise used.
var _ context.Context
var _ grpc.ClientConn

// This is a compile-time assertion to ensure that this generated file
// is compatible with the grpc package it is being compiled against.
const _ = grpc.SupportPackageIsVersion4

// ApplianceAgentClient is the client API for ApplianceAgent service.
//
// For semantics around ctx use and closing/ending streaming RPCs, please refer to https://godoc.org/google.golang.org/grpc#ClientConn.NewStream.
type ApplianceAgentClient interface {
	Ping(ctx context.Context, in *PingRequest, opts ...grpc.CallOption) (*PingResponse, error)
}

type applianceAgentClient struct {
	cc *grpc.ClientConn
}

func NewApplianceAgentClient(cc *grpc.ClientConn) ApplianceAgentClient {
	return &applianceAgentClient{cc}
}

func (c *applianceAgentClient) Ping(ctx context.Context, in *PingRequest, opts ...grpc.CallOption) (*PingResponse, error) {
	out := new(PingResponse)
	err := c.cc.Invoke(ctx, "/virtualappliance.ApplianceAgent/Ping", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ApplianceAgentServer is the server API for ApplianceAgent service.
type ApplianceAgentServer interface {
	Ping(context.Context, *PingRequest) (*PingResponse, error)
}

// UnimplementedApplianceAgentServer can be embedded to have forward compatible implementations.
type UnimplementedApplianceAgentServer struct {
}

func (*UnimplementedApplianceAgentServer) Ping(ctx context.Context, req *PingRequest) (*PingResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method Ping not implemented")
}

func RegisterApplianceAgentServer(s *grpc.Server, srv ApplianceAgentServer) {
	s.RegisterService(&_ApplianceAgent_serviceDesc, srv)
}

func _ApplianceAgent_Ping_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(PingRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(ApplianceAgentServer).Ping(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/virtualappliance.ApplianceAgent/Ping",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(ApplianceAgentServer).Ping(ctx, req.(*PingRequest))
	}
	return interceptor(ctx, in, info, handler)
}

var _ApplianceAgent_serviceDesc = grpc.ServiceDesc{
	ServiceName: "virtualappliance.ApplianceAgent",
	HandlerType: (*ApplianceAgentServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "Ping",
			Handler:    _ApplianceAgent_Ping_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "VirtualAppliance.proto",
}
